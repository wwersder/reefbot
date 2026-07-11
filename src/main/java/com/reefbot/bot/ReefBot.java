package com.reefbot.bot;

import com.reefbot.config.SupportProperties;
import com.reefbot.config.TelegramProperties;
import com.reefbot.dto.BotResponse;
import com.reefbot.entity.Island;
import com.reefbot.entity.Player;
import com.reefbot.enums.PlayerStatus;
import com.reefbot.repository.IslandRepository;
import com.reefbot.service.InventoryImageGenerator;
import com.reefbot.service.IslandMapGenerator;
import com.reefbot.service.MessageDispatcher;
import com.reefbot.service.PlayerService;
import com.reefbot.service.support.SupportGroupHandler;
import com.reefbot.service.support.SupportService;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.telegram.telegrambots.longpolling.util.LongPollingSingleThreadUpdateConsumer;
import org.telegram.telegrambots.meta.api.methods.GetFile;
import org.telegram.telegrambots.meta.api.methods.GetUserProfilePhotos;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto;
import org.telegram.telegrambots.meta.api.objects.InputFile;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.UserProfilePhotos;
import org.telegram.telegrambots.meta.api.objects.photo.PhotoSize;
import org.telegram.telegrambots.meta.api.objects.chatmember.ChatMember;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.io.InputStream;
import java.net.URL;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Telegram long-polling consumer.
 *
 * <p>Масштабирование: библиотека доставляет апдейты последовательно через {@code consume()},
 * но мы сразу диспетчеризируем каждый в пул потоков. Таким образом разные игроки
 * обрабатываются параллельно, а заблокированный поток (например, Thread.sleep при броске
 * кубика) не останавливает обработку остальных.
 *
 * <p>Гонки на одном игроке (два одновременных апдейта) крайне редки и защищены
 * {@code @Version} на сущности Player — второй запрос получит OptimisticLockException
 * и будет повторён в MessageDispatcher.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReefBot implements LongPollingSingleThreadUpdateConsumer {

    /**
     * Пул для обработки апдейтов. CachedThreadPool создаёт потоки по требованию
     * и утилизирует простаивающие — оптимально для нагрузки с пиками.
     */
    private final ExecutorService updatePool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "bot-update");
        t.setDaemon(true);
        return t;
    });

    private final MessageDispatcher       dispatcher;
    private final CallbackDispatcher      callbackDispatcher;
    private final TelegramClient          telegramClient;
    private final TelegramProperties      telegramProperties;
    private final SupportGroupHandler     supportGroupHandler;
    private final SupportService          supportService;
    private final SupportProperties       supportProperties;
    private final PlayerService           playerService;
    private final InventoryImageGenerator inventoryImageGenerator;
    private final IslandMapGenerator      islandMapGenerator;
    private final IslandRepository        islandRepository;

    // ── Consume ───────────────────────────────────────────────────────────

    @Override
    public void consume(Update update) {
        // Немедленно возвращаем управление библиотеке — сама обработка асинхронна
        updatePool.submit(() -> handleUpdate(update));
    }

    private void handleUpdate(Update update) {
        try {
            if (update.hasMyChatMember()) {
                handleMembershipChange(update);
                return;
            }

            if (update.hasCallbackQuery()) {
                callbackDispatcher.dispatch(update.getCallbackQuery());
                return;
            }

            if (!update.hasMessage()) return;

            Message message   = update.getMessage();
            Long    chatId    = message.getChatId();
            boolean isPrivate = "private".equals(message.getChat().getType());

            // Route support group messages (all types) to SupportGroupHandler
            if (!isPrivate && isSupportGroup(chatId)) {
                supportGroupHandler.handle(update);
                return;
            }

            Long telegramId = message.getFrom() != null ? message.getFrom().getId() : null;

            // /chatid — для главного админа в любом чате
            if (message.hasText() && ADMIN_TELEGRAM_ID.equals(telegramId)) {
                if ("/chatid".equals(message.getText().split("@")[0])) {
                    sendResponse(chatId, BotResponse.html("Chat ID: <code>" + chatId + "</code>"));
                    return;
                }
            }

            // /inv and /island — image commands; work in groups and private chats
            if (message.hasText() && telegramId != null) {
                String t = message.getText();
                if ("/inv".equals(t) || t.startsWith("/inv@")) {
                    handleInvCommand(chatId, telegramId, isPrivate);
                    return;
                }
                if ("/island".equals(t) || t.startsWith("/island@")) {
                    handleIslandCommand(chatId, telegramId, isPrivate);
                    return;
                }
            }

            // Private-only from here
            if (!isPrivate) return;
            String username = message.getFrom().getUserName();

            // Relay media / forwarded messages to support group
            boolean isForwarded = message.getForwardFrom() != null
                    || message.getForwardFromChat() != null;
            if (!message.hasText() || isForwarded) {
                Optional<Player> playerOpt = playerService.findByTelegramId(telegramId);
                if (playerOpt.isPresent()) {
                    BotResponse mediaResp = supportService.relayPlayerMedia(
                            playerOpt.get(), message, isForwarded);
                    if (mediaResp != null) sendResponse(chatId, mediaResp);
                }
                return;
            }

            String text = message.getText();
            BotResponse response = dispatcher.dispatch(telegramId, username, text, isPrivate);
            if (response != null) {
                sendResponse(chatId, response);
            }

        } catch (Exception e) {
            log.error("Unhandled error processing update {}", update.getUpdateId(), e);
        }
    }

    private static final Long ADMIN_TELEGRAM_ID = 920215477L;

    // ── /inv command ──────────────────────────────────────────────────────

    private void handleInvCommand(Long chatId, Long telegramId, boolean isPrivate) {
        Optional<Player> pOpt = playerService.findByTelegramId(telegramId);
        if (pOpt.isEmpty() || pOpt.get().getStatus() != PlayerStatus.ACTIVE) {
            if (isPrivate) {
                sendResponse(chatId, new BotResponse("Сначала пройди регистрацию: напиши /start"));
            }
            return;
        }

        Player p      = pOpt.get();
        Island island = islandRepository.findByPlayer(p).orElse(new Island());

        try {
            byte[] avatar = loadUserAvatar(telegramId);
            byte[] img    = inventoryImageGenerator.generate(p, island, avatar);
            String caption = (p.getUsername() != null ? "@" + p.getUsername() : "Игрок")
                    + ", ваш инвентарь";
            sendInventoryPhoto(chatId, img, caption);
        } catch (Exception e) {
            log.error("Failed to generate inventory for player {}", telegramId, e);
            sendResponse(chatId, new BotResponse("Не удалось сгенерировать инвентарь. Попробуй ещё раз."));
        }
    }

    // ── /island command ───────────────────────────────────────────────────────

    private void handleIslandCommand(Long chatId, Long telegramId, boolean isPrivate) {
        Optional<Player> pOpt = playerService.findByTelegramId(telegramId);
        if (pOpt.isEmpty() || pOpt.get().getStatus() != PlayerStatus.ACTIVE) {
            if (isPrivate) {
                sendResponse(chatId, new BotResponse("Сначала пройди регистрацию: напиши /start"));
            }
            return;
        }

        Player p      = pOpt.get();
        Island island = islandRepository.findByPlayer(p).orElse(new Island());

        try {
            byte[] img = islandMapGenerator.generate(p, island);
            String caption = "🗺 Карта острова «" + (island.getName() != null ? island.getName() : "—") + "»";
            sendIslandPhoto(chatId, img, caption);
        } catch (Exception e) {
            log.error("Failed to generate island map for player {}", telegramId, e);
            sendResponse(chatId, new BotResponse("Не удалось сгенерировать карту острова. Попробуй ещё раз."));
        }
    }

    /**
     * Fetches the player's Telegram profile photo as raw bytes (JPEG).
     * Returns null if the user has no photo or the API call fails.
     */
    private byte[] loadUserAvatar(Long telegramId) {
        try {
            GetUserProfilePhotos request = GetUserProfilePhotos.builder()
                    .userId(telegramId)
                    .limit(1)
                    .build();
            UserProfilePhotos photos = telegramClient.execute(request);
            if (photos == null || photos.getTotalCount() == 0) return null;

            List<PhotoSize> sizes = photos.getPhotos().get(0);
            PhotoSize photo = sizes.stream()
                    .max(Comparator.comparingInt(ps -> ps.getFileSize() != null ? ps.getFileSize() : 0))
                    .orElse(null);
            if (photo == null) return null;

            GetFile gf = GetFile.builder().fileId(photo.getFileId()).build();
            org.telegram.telegrambots.meta.api.objects.File file = telegramClient.execute(gf);
            if (file == null || file.getFilePath() == null) return null;

            String urlStr = "https://api.telegram.org/file/bot"
                    + telegramProperties.getToken() + "/" + file.getFilePath();
            try (InputStream in = new URL(urlStr).openStream()) {
                return in.readAllBytes();
            }
        } catch (Exception e) {
            log.debug("Could not load avatar for user {}: {}", telegramId, e.getMessage());
            return null;
        }
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────

    @PreDestroy
    public void shutdown() {
        updatePool.shutdown();
        try {
            if (!updatePool.awaitTermination(10, TimeUnit.SECONDS)) {
                updatePool.shutdownNow();
                log.warn("Update pool did not terminate gracefully, forcing shutdown");
            }
        } catch (InterruptedException e) {
            updatePool.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    // ── Membership ─────────────────────────────────────────────────────────

    private void handleMembershipChange(Update update) {
        ChatMember newMember = update.getMyChatMember().getNewChatMember();
        ChatMember oldMember = update.getMyChatMember().getOldChatMember();

        boolean wasAdded = isInactive(oldMember) && isActive(newMember);
        if (!wasAdded) return;

        // TODO: приветственный текст для беседы
    }

    private boolean isActive(ChatMember member) {
        String status = member.getStatus();
        return "member".equals(status) || "administrator".equals(status);
    }

    private boolean isInactive(ChatMember member) {
        String status = member.getStatus();
        return "left".equals(status) || "kicked".equals(status);
    }

    // ── Send ──────────────────────────────────────────────────────────────

    public void sendResponse(Long chatId, BotResponse response) {
        try {
            if (StringUtils.hasText(response.photoPath())) {
                SendPhoto.SendPhotoBuilder<?, ?> builder = SendPhoto.builder()
                        .chatId(chatId)
                        .photo(createInputFile(response.photoPath()))
                        .caption(response.text());

                if (response.keyboard() != null) builder.replyMarkup(response.keyboard());

                List<?> entities = response.entities();
                if (entities != null && !entities.isEmpty()) {
                    builder.captionEntities(response.entities());
                }

                telegramClient.execute(builder.build());
                return;
            }

            SendMessage.SendMessageBuilder<?, ?> builder = SendMessage.builder()
                    .chatId(chatId)
                    .text(response.text());

            if (response.keyboard() != null) builder.replyMarkup(response.keyboard());

            if (response.parseMode() != null) {
                builder.parseMode(response.parseMode());
            } else {
                List<?> entities = response.entities();
                if (entities != null && !entities.isEmpty()) {
                    builder.entities(response.entities());
                }
            }

            telegramClient.execute(builder.build());

        } catch (TelegramApiException e) {
            log.error("Failed to send response to chat {}: {}", chatId, e.getMessage());
            // Don't send followUp if primary message failed — it would be out of context
            return;
        }

        if (response.followUp() != null) {
            sendResponse(chatId, response.followUp());
        }
    }

    private InputFile createInputFile(String photoPath) {
        InputStream stream = ReefBot.class.getClassLoader().getResourceAsStream(photoPath);
        if (stream == null) {
            throw new IllegalArgumentException("Photo resource not found: " + photoPath);
        }
        String fileName = photoPath.substring(photoPath.lastIndexOf('/') + 1);
        return new InputFile(stream, fileName);
    }

    /** Sends a dynamically generated image (byte array) as a Telegram photo with caption. */
    private void sendInventoryPhoto(Long chatId, byte[] imageBytes, String caption) {
        try {
            InputFile photo = new InputFile(
                    new java.io.ByteArrayInputStream(imageBytes), "inventory.png");
            telegramClient.execute(SendPhoto.builder()
                    .chatId(chatId)
                    .photo(photo)
                    .caption(caption)
                    .build());
        } catch (TelegramApiException e) {
            log.error("Failed to send inventory photo to chat {}: {}", chatId, e.getMessage());
        }
    }

    /** Sends a dynamically generated island map image as a Telegram photo with caption. */
    private void sendIslandPhoto(Long chatId, byte[] imageBytes, String caption) {
        try {
            InputFile photo = new InputFile(
                    new java.io.ByteArrayInputStream(imageBytes), "island.png");
            telegramClient.execute(SendPhoto.builder()
                    .chatId(chatId)
                    .photo(photo)
                    .caption(caption)
                    .build());
        } catch (TelegramApiException e) {
            log.error("Failed to send island photo to chat {}: {}", chatId, e.getMessage());
        }
    }

    private boolean isSupportGroup(Long chatId) {
        Long groupId = supportProperties.getGroupChatId();
        return groupId != null && groupId != 0 && groupId.equals(chatId);
    }
}
