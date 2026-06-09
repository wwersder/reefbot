package com.reefbot.bot.handlers;

import com.reefbot.bot.CallbackHandler;
import com.reefbot.entity.Player;
import com.reefbot.enums.FishingSpot;
import com.reefbot.enums.PlayerScreen;
import com.reefbot.repository.PlayerRepository;
import com.reefbot.service.game.FishingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

@Slf4j
@Component
@RequiredArgsConstructor
public class CastCallbackHandler implements CallbackHandler {

    private final FishingService fishingService;
    private final PlayerRepository playerRepository;
    private final TelegramClient telegramClient;

    @Override
    public String getPrefix() {
        return "cast";
    }

    @Override
    public void handle(String payload, long chatId, int messageId, Player player) {
        if (player == null) return;

        FishingSpot spot;
        try {
            spot = FishingSpot.valueOf(payload);
        } catch (IllegalArgumentException e) {
            log.warn("Unknown fishing spot in cast callback: {}", payload);
            return;
        }

        fishingService.startFishing(player, spot);
        player.getState().setCurrentScreen(PlayerScreen.FISHING_ACTIVE);
        playerRepository.save(player);

        String text = String.format("""
                ⏳ Удочка заброшена %s

                Возвращайся через %d мин — улов будет ждать.
                """, spot.getDisplayName().toLowerCase(), spot.getDurationMinutes());

        try {
            telegramClient.execute(EditMessageText.builder()
                    .chatId(chatId)
                    .messageId(messageId)
                    .text(text)
                    .build());
        } catch (TelegramApiException e) {
            log.error("Failed to edit cast message", e);
        }
    }
}
