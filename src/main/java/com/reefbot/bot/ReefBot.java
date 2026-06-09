package com.reefbot.bot;

import com.reefbot.dto.BotResponse;
import com.reefbot.service.MessageDispatcher;
import lombok.RequiredArgsConstructor;
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

@Component
@RequiredArgsConstructor
public class ReefBot implements LongPollingSingleThreadUpdateConsumer {

    private final MessageDispatcher dispatcher;
    private final TelegramClient telegramClient;

    @Override
    public void consume(Update update) {
        if (update.hasMyChatMember()) {
            handleMembershipChange(update);
            return;
        }

        if (!update.hasMessage() || !update.getMessage().hasText()) {
            return;
        }

        Long telegramId = update.getMessage().getFrom().getId();
        Long chatId = update.getMessage().getChatId();
        String username = update.getMessage().getFrom().getUserName();
        String text = update.getMessage().getText();
        boolean isPrivate = "private".equals(update.getMessage().getChat().getType());

        BotResponse response = dispatcher.dispatch(telegramId, username, text, isPrivate);

        if (response != null) {
            sendResponse(chatId, response);
        }
    }

    private void handleMembershipChange(Update update) {
        ChatMember newMember = update.getMyChatMember().getNewChatMember();
        ChatMember oldMember = update.getMyChatMember().getOldChatMember();

        boolean wasAdded = isInactive(oldMember) && isActive(newMember);
        if (!wasAdded) {
            return;
        }

        Long chatId = update.getMyChatMember().getChat().getId();
        // TODO: добавить приветственный текст для беседы
    }

    private boolean isActive(ChatMember member) {
        String status = member.getStatus();
        return "member".equals(status) || "administrator".equals(status);
    }

    private boolean isInactive(ChatMember member) {
        String status = member.getStatus();
        return "left".equals(status) || "kicked".equals(status);
    }

    private void sendResponse(Long chatId, BotResponse response) {
        try {
            if (StringUtils.hasText(response.photoPath())) {
                SendPhoto.SendPhotoBuilder<?, ?> builder = SendPhoto.builder()
                        .chatId(chatId)
                        .photo(createInputFile(response.photoPath()))
                        .caption(response.text());

                if (response.keyboard() != null) {
                    builder.replyMarkup(response.keyboard());
                }

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

            if (response.keyboard() != null) {
                builder.replyMarkup(response.keyboard());
            }

            List<?> entities = response.entities();
            if (entities != null && !entities.isEmpty()) {
                builder.entities(response.entities());
            }

            telegramClient.execute(builder.build());
        } catch (TelegramApiException e) {
            throw new RuntimeException("Failed to send Telegram response", e);
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
