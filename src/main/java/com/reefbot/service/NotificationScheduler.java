package com.reefbot.service;

import com.reefbot.entity.Player;
import com.reefbot.enums.PlayerScreen;
import com.reefbot.repository.PlayerRepository;
import com.reefbot.service.game.handlers.FishingResultHandler;
import com.reefbot.util.KeyboardBuilder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationScheduler {

    private static final String FISHING_DONE_TEXT = """
            🔔 Улов готов!

            Удочка ждёт тебя %s.
            Нажми «✅ Забрать улов» чтобы получить рыбу.
            """;

    private final PlayerRepository playerRepository;
    private final TelegramClient telegramClient;

    /**
     * Every 30 seconds: find players who finished fishing but haven't collected yet.
     * Transitions them to FISHING_RESULT and sends a notification.
     */
    @Scheduled(fixedDelay = 30_000)
    public void notifyFishingComplete() {
        List<Player> ready = playerRepository.findFishingReady(LocalDateTime.now());

        for (Player player : ready) {
            try {
                // Mark notified first to prevent duplicate sends on next scheduler run
                player.setFishingNotified(true);
                // Always transition to FISHING_RESULT so BTN_COLLECT routes correctly
                player.setCurrentScreen(PlayerScreen.FISHING_RESULT);
                playerRepository.save(player);

                String spotName = player.getFishingSpot() != null
                        ? player.getFishingSpot().getDisplayName().toLowerCase()
                        : "у берега";

                String text = FISHING_DONE_TEXT.formatted(spotName);

                telegramClient.execute(SendMessage.builder()
                        .chatId(String.valueOf(player.getTelegramId()))
                        .text(text)
                        .replyMarkup(KeyboardBuilder.builder()
                                .row(FishingResultHandler.BTN_COLLECT)
                                .build())
                        .build());

            } catch (TelegramApiException e) {
                log.error("Failed to notify player {} about fishing result", player.getTelegramId(), e);
            }
        }
    }
}
