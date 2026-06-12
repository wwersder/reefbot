package com.reefbot.service;

import com.reefbot.entity.Player;
import com.reefbot.entity.PlayerFishing;
import com.reefbot.entity.PlayerTide;
import com.reefbot.repository.PlayerFishingRepository;
import com.reefbot.repository.PlayerRepository;
import com.reefbot.repository.PlayerTideRepository;
import com.reefbot.service.game.TideService;
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

    private static final String TIDE_TEXT = """
            🌊 Прилив!

            Волна принесла что-то на берег.
            Загляни в зону Берега — окно открыто 40 минут.
            """;

    private final PlayerFishingRepository playerFishingRepository;
    private final PlayerTideRepository playerTideRepository;
    private final TideService tideService;
    private final TelegramClient telegramClient;

    @Scheduled(fixedDelay = 30_000)
    @Transactional
    public void notifyFishingComplete() {
        List<PlayerFishing> ready = playerFishingRepository.findFishingReady(LocalDateTime.now());

        for (PlayerFishing fishing : ready) {
            Player player = fishing.getPlayer();
            try {
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

    @Scheduled(fixedDelay = 60_000)
    @Transactional
    public void notifyTideActive() {
        List<PlayerTide> activeTides = playerTideRepository.findActiveTidesNotNotified(LocalDateTime.now());

        for (PlayerTide tide : activeTides) {
            Player player = tide.getPlayer();
            try {
                tide.setTideNotified(true);
                playerTideRepository.save(tide);

                telegramClient.execute(SendMessage.builder()
                        .chatId(String.valueOf(player.getTelegramId()))
                        .text(TIDE_TEXT)
                        .build());

            } catch (TelegramApiException e) {
                log.error("Failed to notify player {} about tide", player.getTelegramId(), e);
            }
        }
    }

    /** Schedule first tide for players migrated before tide feature existed. */
    @Scheduled(fixedDelay = 300_000) // every 5 min
    @Transactional
    public void scheduleFirstTidesForLegacyPlayers() {
        List<PlayerTide> unscheduled = playerTideRepository.findByTideAvailableAtIsNull();
        for (PlayerTide tide : unscheduled) {
            tideService.scheduleFirstTide(tide.getPlayer());
        }
    }
}
