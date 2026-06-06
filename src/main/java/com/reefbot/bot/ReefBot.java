// ReefBot.java
package com.reefbot.bot;

import com.reefbot.dto.BotResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.longpolling.util.LongPollingSingleThreadUpdateConsumer;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto;
import org.telegram.telegrambots.meta.api.objects.InputFile;
import org.telegram.telegrambots.meta.api.objects.Update;
import com.reefbot.service.registration.OnboardingService;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

@Component
@RequiredArgsConstructor
public class ReefBot implements LongPollingSingleThreadUpdateConsumer {

    private final OnboardingService onboardingService;
    private final TelegramClient telegramClient;

    @Override
    public void consume(Update update) {
        if (!update.hasMessage() || !update.getMessage().hasText()) {
            return;
        }

        Long telegramId = update.getMessage().getFrom().getId();
        Long chatId = update.getMessage().getChatId();
        String text = update.getMessage().getText();

        BotResponse response = onboardingService.processMessage(telegramId, text);

        if (response != null) {
            sendResponse(chatId, response);
        }
    }

    private void sendResponse(Long chatId, BotResponse response) {
        try {
            if (response.photo() != null) {
                telegramClient.execute(SendPhoto.builder()
                        .chatId(chatId)
                        .photo(new InputFile(new java.io.File(response.photo())))
                        .caption(response.text())
                        .captionEntities(response.entities())
                        .replyMarkup(response.keyboard())
                        .build()
                );
            } else {
                telegramClient.execute(SendMessage.builder()
                        .chatId(chatId)
                        .text(response.text())
                        .entities(response.entities())
                        .replyMarkup(response.keyboard())
                        .build()
                );
            }
        } catch (TelegramApiException e) {
            throw new RuntimeException(e);
        }
    }
}