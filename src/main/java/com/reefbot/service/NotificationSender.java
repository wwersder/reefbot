package com.reefbot.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationSender {

    private final TelegramClient telegramClient;

    public void send(Long chatId, String text) {
        try {
            telegramClient.execute(
                    SendMessage.builder()
                            .chatId(chatId)
                            .text(text)
                            .build()
            );
        } catch (TelegramApiException e) {
            log.error("Failed to send notification to chat {}: {}", chatId, e.getMessage());
        }
    }
}
