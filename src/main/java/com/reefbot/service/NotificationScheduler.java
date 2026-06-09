package com.reefbot.service;

import com.reefbot.entity.Player;
import com.reefbot.entity.PlayerFishing;
import com.reefbot.repository.PlayerFishingRepository;
import com.reefbot.repository.PlayerRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
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
            Возвращайся и забери рыбу.
            """;

    private final PlayerFishingRepository playerFishingRepository;
    private final TelegramClient telegramClient;

    @Scheduled(fixedDelay = 30_000)
    @Transactional
    public void notifyFishingComplete() {
        List<PlayerFishing> ready = playerFishingRepository.findFishingReady(LocalDateTime.now());

        for (PlayerFishing fishing : ready) {
            Player player = fishing.getPlayer();
            try {
                // Mark notified first to prevent duplicate sends on next run
                fishing.setFishingNotified(true);
                playerFishingRepository.save(fishing);

                String spotName = fishing.getFishingSpot() != null
                        ? fishing.getFishingSpot().getDisplayName().toLowerCase()
                        : "у берега";

                telegramClient.execute(SendMessage.builder()
                        .chatId(String.valueOf(player.getTelegramId()))
                        .text(FISHING_DONE_TEXT.formatted(spotName))
                        .build());

            } catch (TelegramApiException e) {
                log.error("Failed to notify player {} about fishing result", player.getTelegramId(), e);
            }
        }
    }
}
