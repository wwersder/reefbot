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

            if (!update.hasMessage() || !update.getMessage().hasText()) {
                return;
            }

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
            // Не бросаем — ошибка отправки не должна ронять поток
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
