package com.reefbot.bot;

import com.reefbot.dto.BotResponse;
import com.reefbot.service.MessageDispatcher;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.telegram.telegrambots.longpolling.util.LongPollingSingleThreadUpdateConsumer;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto;
import org.telegram.telegrambots.meta.api.objects.InputFile;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.chatmember.ChatMember;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.io.InputStream;
import java.util.List;
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

    private final MessageDispatcher dispatcher;
    private final CallbackDispatcher callbackDispatcher;
    private final TelegramClient telegramClient;

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
            Long chatId       = message.getChatId();
            boolean isPrivate = "private".equals(message.getChat().getType());

            // Debug: log all group messages
            if (!isPrivate) {
                log.info("[GROUP] chatId={} type={} hasText={} text={}",
                        chatId, message.getChat().getType(), message.hasText(),
                        message.hasText() ? message.getText() : "");
            }

            // /chatid — available in any group chat for Telegram admins
            if (!isPrivate && message.hasText()) {
                String cmd = message.getText().split("@")[0];
                if ("/chatid".equals(cmd)) {
                    handleChatIdCommand(message, chatId);
                    return;
                }
            }

            Long telegramId = message.getFrom() != null ? message.getFrom().getId() : null;

            // /chatid — для главного админа в любом чате
            if (!isPrivate && message.hasText() && ADMIN_TELEGRAM_ID.equals(telegramId)) {
                if ("/chatid".equals(message.getText().split("@")[0])) {
                    sendResponse(chatId, BotResponse.html("Chat ID: <code>" + chatId + "</code>"));
                    return;
                }
            }

            // Private-only from here
            if (!isPrivate) return;

            Long telegramId = update.getMessage().getFrom().getId();
            Long chatId     = update.getMessage().getChatId();
            String username = update.getMessage().getFrom().getUserName();
            String text     = update.getMessage().getText();
            boolean isPrivate = "private".equals(update.getMessage().getChat().getType());

            BotResponse response = dispatcher.dispatch(telegramId, username, text, isPrivate);

            if (response != null) {
                sendResponse(chatId, response);
            }

        } catch (Exception e) {
            log.error("Unhandled error processing update {}", update.getUpdateId(), e);
        }
    }

    /**
     * Responds to /chatid in any group chat, but only for Telegram chat admins.
     * Use this to discover chat IDs for SUPPORT_GROUP_CHAT_ID config.
     */
    private static final Long ADMIN_TELEGRAM_ID = 920215477L;

    private void handleChatIdCommand(Message message, Long chatId) {
        if (!ADMIN_TELEGRAM_ID.equals(message.getFrom().getId())) return;
        try {
            telegramClient.execute(SendMessage.builder()
                    .chatId(chatId)
                    .text("Chat ID: <code>" + chatId + "</code>")
                    .parseMode("HTML")
                    .build());
        } catch (TelegramApiException e) {
            log.error("Failed to handle /chatid in chat {}", chatId, e);
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
}
