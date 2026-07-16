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
import jakarta.annotation.PostConstruct;
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

import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestTemplate;

import java.io.InputStream;
import java.net.URL;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

    // ── Init ──────────────────────────────────────────────────────────────

    /**
     * Registers bot commands in Telegram at startup.
     *
     * <p>Player-facing commands (/island, /inv) are registered as ephemeral for all
     * group chats (Bot API 10.2): when invoked in a group, the command message itself
     * is hidden from other members, and the bot's response goes only to the sender.
     *
     * <p>The admin command /adm is registered as ephemeral for chat administrators
     * only, so regular members don't even see it in the command menu.
     */
    @PostConstruct
    private void registerCommands() {
        // Player commands — visible in groups, but invocation is ephemeral
        boolean ok1 = sendRawApiRequest("setMyCommands", Map.of(
                "commands", List.of(
                        Map.of("command", "island", "description", "🗺 Карта острова",   "is_ephemeral", true),
                        Map.of("command", "inv",    "description", "🎒 Мой инвентарь",  "is_ephemeral", true)
                ),
                "scope", Map.of("type", "all_group_chats")
        ));

        // Admin commands — visible only to chat admins, ephemeral
        boolean ok2 = sendRawApiRequest("setMyCommands", Map.of(
                "commands", List.of(
                        Map.of("command", "adm",  "description", "⚙️ Панель администратора",  "is_ephemeral", true),
                        Map.of("command", "kick", "description", "🚫 Исключить пользователя", "is_ephemeral", true),
                        Map.of("command", "mute", "description", "🔇 Заглушить пользователя", "is_ephemeral", true)
                ),
                "scope", Map.of("type", "all_chat_administrators")
        ));

        log.info("Registered commands: player_group={}, admin={}", ok1, ok2);
    }

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
                if ("/ephem".equals(t) || t.startsWith("/ephem@")) {
                    handleEphemeralTest(chatId, telegramId, message.getFrom().getFirstName(), isPrivate);
                    return;
                }
                // Ephemeral admin command — message_id == 0 when sent as ephemeral command in a group
                if (("/adm".equals(t) || t.startsWith("/adm@")) && ADMIN_TELEGRAM_ID.equals(telegramId)) {
                    handleAdminCommand(chatId, telegramId, isPrivate);
                    return;
                }
                // Admin moderation commands — group-only, reply-based, ephemeral response
                if (("/kick".equals(t) || t.startsWith("/kick@")) && ADMIN_TELEGRAM_ID.equals(telegramId) && !isPrivate) {
                    handleKickCommand(chatId, telegramId, message);
                    return;
                }
                if ((t.startsWith("/mute") && (t.length() == 5 || t.charAt(5) == '@' || t.charAt(5) == ' '))
                        && ADMIN_TELEGRAM_ID.equals(telegramId) && !isPrivate) {
                    handleMuteCommand(chatId, telegramId, t, message);
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
            // In groups — send ephemeral so only the requesting player sees their inventory
            Long receiver = isPrivate ? null : telegramId;
            if (receiver != null) {
                sendRawPhoto(chatId, img, "inventory.png", caption, receiver);
            } else {
                sendInventoryPhoto(chatId, img, caption);
            }
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
            // In groups — send ephemeral so only the requesting player sees their map
            Long receiver = isPrivate ? null : telegramId;
            if (receiver != null) {
                sendRawPhoto(chatId, img, "island.png", caption, receiver);
            } else {
                sendIslandPhoto(chatId, img, caption);
            }
        } catch (Exception e) {
            log.error("Failed to generate island map for player {}", telegramId, e);
            sendResponse(chatId, new BotResponse("Не удалось сгенерировать карту острова. Попробуй ещё раз."));
        }
    }

    // ── /adm command (admin-only, ephemeral in groups) ────────────────────────

    /**
     * Admin command handler. In groups it's registered as ephemeral (Bot API 10.2):
     * nobody else sees the command or the response.
     */
    private void handleAdminCommand(Long chatId, Long telegramId, boolean isPrivate) {
        long playerCount = playerService.countAll();
        String text = "⚙️ <b>ReefBot Admin</b>\n\n"
                + "👥 Игроков: <b>" + playerCount + "</b>\n"
                + "🤖 Bot API 10.2 — ephemeral\n"
                + "<i>Только ты видишь это сообщение</i>";

        if (isPrivate) {
            sendResponse(chatId, BotResponse.html(text));
        } else {
            Map<String, Object> body = new HashMap<>();
            body.put("chat_id", chatId);
            body.put("text", text);
            body.put("parse_mode", "HTML");
            body.put("receiver_user_id", telegramId);
            boolean sent = sendRawApiRequest("sendMessage", body);
            if (!sent) sendResponse(chatId, BotResponse.html(text));
        }
    }

    // ── /kick command (admin-only, group-only, reply-based, ephemeral) ──────────

    /**
     * Bans (kicks) the user whose message the admin replied to.
     * The command and bot's response are ephemeral — invisible to other members.
     */
    private void handleKickCommand(Long chatId, Long adminId, Message message) {
        Message replied = message.getReplyToMessage();
        if (replied == null || replied.getFrom() == null) {
            sendEphemeral(chatId, adminId,
                    "⚠️ Используй <code>/kick</code> как <b>ответ</b> на сообщение пользователя.");
            return;
        }
        Long targetId   = replied.getFrom().getId();
        String name     = replied.getFrom().getFirstName();

        if (targetId.equals(adminId)) {
            sendEphemeral(chatId, adminId, "🤦 Нельзя кикнуть самого себя.");
            return;
        }

        boolean ok = sendRawApiRequest("banChatMember", Map.of(
                "chat_id", chatId, "user_id", targetId));

        if (ok) {
            sendEphemeral(chatId, adminId, "✅ <b>" + name + "</b> исключён из чата.");
        } else {
            sendEphemeral(chatId, adminId,
                    "❌ Не удалось исключить <b>" + name + "</b>. Проверь права бота.");
        }
    }

    // ── /mute command (admin-only, group-only, reply-based, ephemeral) ──────────

    /**
     * Restricts (mutes) the user whose message the admin replied to.
     * Duration: {@code /mute} = 1h, {@code /mute 30m} = 30 min, {@code /mute 2h} = 2h, {@code /mute 1d} = 1 day.
     */
    private void handleMuteCommand(Long chatId, Long adminId, String commandText, Message message) {
        Message replied = message.getReplyToMessage();
        if (replied == null || replied.getFrom() == null) {
            sendEphemeral(chatId, adminId,
                    "⚠️ Используй <code>/mute [длительность]</code> как <b>ответ</b> на сообщение пользователя.\n"
                    + "Примеры: <code>/mute</code> (1ч), <code>/mute 30m</code>, <code>/mute 2h</code>, <code>/mute 1d</code>");
            return;
        }
        Long targetId   = replied.getFrom().getId();
        String name     = replied.getFrom().getFirstName();

        if (targetId.equals(adminId)) {
            sendEphemeral(chatId, adminId, "🤦 Нельзя заглушить самого себя.");
            return;
        }

        int durationMinutes = parseMuteDuration(commandText);
        long untilDate = System.currentTimeMillis() / 1000L + (long) durationMinutes * 60;

        boolean ok = sendRawApiRequest("restrictChatMember", Map.of(
                "chat_id", chatId,
                "user_id", targetId,
                "permissions", Map.of("can_send_messages", false,
                        "can_send_media_messages", false,
                        "can_send_other_messages", false),
                "until_date", untilDate));

        String durationStr = formatDuration(durationMinutes);
        if (ok) {
            sendEphemeral(chatId, adminId,
                    "🔇 <b>" + name + "</b> заглушён на " + durationStr + ".");
        } else {
            sendEphemeral(chatId, adminId,
                    "❌ Не удалось заглушить <b>" + name + "</b>. Проверь права бота.");
        }
    }

    /** Parses duration from mute command text: "/mute 2h", "/mute 30m", "/mute 1d". Default 60 min. */
    private static int parseMuteDuration(String commandText) {
        String normalized = commandText.replaceFirst("@\\w+", "").trim();
        String[] parts = normalized.split("\\s+", 2);
        if (parts.length < 2) return 60;
        return parseDuration(parts[1].toLowerCase().trim());
    }

    private static int parseDuration(String arg) {
        try {
            if (arg.endsWith("d")) return Integer.parseInt(arg.substring(0, arg.length() - 1)) * 1440;
            if (arg.endsWith("h")) return Integer.parseInt(arg.substring(0, arg.length() - 1)) * 60;
            if (arg.endsWith("m")) return Integer.parseInt(arg.substring(0, arg.length() - 1));
            return Math.max(1, Integer.parseInt(arg)) * 60;
        } catch (NumberFormatException e) {
            return 60;
        }
    }

    private static String formatDuration(int minutes) {
        if (minutes >= 1440) {
            int d = minutes / 1440;
            return d + " д" + (d == 1 ? "ень" : d < 5 ? "ня" : "ней");
        }
        if (minutes >= 60) {
            int h = minutes / 60, m = minutes % 60;
            return h + " ч" + (m > 0 ? " " + m + " мин" : "");
        }
        return minutes + " мин";
    }

    /** Sends an ephemeral message visible only to {@code receiverId} in the group, or plain in DM. */
    private void sendEphemeral(Long chatId, Long receiverId, String html) {
        Map<String, Object> body = new HashMap<>();
        body.put("chat_id", chatId);
        body.put("text", html);
        body.put("parse_mode", "HTML");
        body.put("receiver_user_id", receiverId);
        boolean sent = sendRawApiRequest("sendMessage", body);
        if (!sent) sendResponse(chatId, BotResponse.html(html));
    }

    // ── /ephem command (Bot API 10.2 test) ────────────────────────────────────

    /**
     * Sends an ephemeral message visible only to the requesting user (Bot API 10.2).
     * In groups: passes receiver_user_id — the message is invisible to everyone else.
     * In private chats: uses sendResponse() directly (ephemeral has no meaning in DMs).
     */
    private void handleEphemeralTest(Long chatId, Long telegramId, String firstName, boolean isPrivate) {
        if (isPrivate) {
            sendResponse(chatId, BotResponse.html(
                    "🤫 <b>Эфемерные сообщения</b>\n\n"
                    + "В личке эффекта нет — тут и так только ты. "
                    + "Попробуй <code>/ephem</code> в группе: бот ответит так, что только ты увидишь!"));
            return;
        }

        // Group: send ephemeral via raw API (Bot API 10.2 feature)
        String text = "🤫 <b>Это эфемерное сообщение!</b>\n\n"
                + "Привет, " + firstName + "! Только ты видишь этот текст — "
                + "остальные участники чата его не видят.\n\n"
                + "<i>Bot API 10.2 — Ephemeral Messages</i>";

        Map<String, Object> body = new HashMap<>();
        body.put("chat_id", chatId);
        body.put("text", text);
        body.put("parse_mode", "HTML");
        body.put("receiver_user_id", telegramId);

        boolean sent = sendRawApiRequest("sendMessage", body);
        if (!sent) {
            // Fallback: send as regular public message
            sendResponse(chatId, BotResponse.html(text + "\n\n⚠️ <i>(ephemeral не поддерживается)</i>"));
        }
    }

    /**
     * Makes a raw HTTP POST to the Bot API endpoint.
     * Used for features not yet supported by the TelegramBots Java library.
     * Returns true on success, false on any error.
     */
    private boolean sendRawApiRequest(String method, Map<String, Object> body) {
        String url = "https://api.telegram.org/bot" + telegramProperties.getToken() + "/" + method;
        try {
            RestTemplate rest = new RestTemplate();
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
            String response = rest.postForObject(url, entity, String.class);
            log.debug("Raw API {} response: {}", method, response);
            return response != null && response.contains("\"ok\":true");
        } catch (Exception e) {
            log.error("Raw API call to {} failed: {}", method, e.getMessage());
            return false;
        }
    }

    /**
     * Sends a photo via raw Bot API — supports ephemeral (receiver_user_id).
     */
    private void sendRawPhoto(Long chatId, byte[] imageBytes, String fileName,
                              String caption, Long receiverUserId) {
        String url = "https://api.telegram.org/bot" + telegramProperties.getToken() + "/sendPhoto";
        try {
            org.springframework.http.client.SimpleClientHttpRequestFactory factory =
                    new org.springframework.http.client.SimpleClientHttpRequestFactory();
            RestTemplate rest = new RestTemplate(factory);

            org.springframework.core.io.ByteArrayResource resource =
                    new org.springframework.core.io.ByteArrayResource(imageBytes) {
                        @Override public String getFilename() { return fileName; }
                    };

            org.springframework.util.LinkedMultiValueMap<String, Object> form =
                    new org.springframework.util.LinkedMultiValueMap<>();
            form.add("chat_id", chatId.toString());
            form.add("photo", resource);
            form.add("caption", caption);
            form.add("parse_mode", "HTML");
            if (receiverUserId != null) {
                form.add("receiver_user_id", receiverUserId.toString());
            }

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.MULTIPART_FORM_DATA);
            HttpEntity<org.springframework.util.MultiValueMap<String, Object>> entity =
                    new HttpEntity<>(form, headers);

            rest.postForObject(url, entity, String.class);
        } catch (Exception e) {
            log.error("Raw sendPhoto failed: {}", e.getMessage());
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
