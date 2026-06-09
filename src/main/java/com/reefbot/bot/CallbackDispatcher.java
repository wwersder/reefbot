package com.reefbot.bot;

import com.reefbot.entity.Player;
import com.reefbot.service.PlayerService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class CallbackDispatcher {

    private final List<CallbackHandler> handlers;
    private final PlayerService playerService;
    private final TelegramClient telegramClient;

    private final Map<String, CallbackHandler> handlerMap = new HashMap<>();

    @PostConstruct
    private void init() {
        for (CallbackHandler h : handlers) {
            handlerMap.put(h.getPrefix(), h);
        }
    }

    public void dispatch(CallbackQuery callbackQuery) {
        String data = callbackQuery.getData();
        long chatId = callbackQuery.getMessage().getChatId();
        int messageId = callbackQuery.getMessage().getMessageId();
        Long telegramId = callbackQuery.getFrom().getId();

        try {
            // Answer immediately to stop the loading spinner
            telegramClient.execute(AnswerCallbackQuery.builder()
                    .callbackQueryId(callbackQuery.getId())
                    .build());
        } catch (TelegramApiException e) {
            log.warn("Failed to answer callback query", e);
        }

        if (data == null || !data.contains(":")) {
            log.warn("Malformed callback data: {}", data);
            return;
        }

        String prefix = data.substring(0, data.indexOf(':'));
        String payload = data.substring(data.indexOf(':') + 1);

        CallbackHandler handler = handlerMap.get(prefix);
        if (handler == null) {
            log.warn("No handler for callback prefix: {}", prefix);
            return;
        }

        Player player = playerService.findByTelegramId(telegramId).orElse(null);
        handler.handle(payload, chatId, messageId, player);
    }
}
