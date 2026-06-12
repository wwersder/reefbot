package com.reefbot.service;

import com.reefbot.entity.PlayerFishing;
import com.reefbot.entity.PlayerTide;
import com.reefbot.repository.PlayerFishingRepository;
import com.reefbot.repository.PlayerRepository;
import com.reefbot.repository.PlayerTideRepository;
import com.reefbot.service.game.TideService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Планировщик уведомлений.
 *
 * <p><b>Ключевой принцип масштабирования:</b> транзакция с записью в БД и вызов Telegram API
 * разделены. Транзакция фиксируется до первого API-вызова — строки не блокируются на время
 * сетевых запросов. Это критично при 1000+ игроках: при старой схеме 500 уведомлений × 500мс
 * держали транзакцию открытой 250 секунд.
 */
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
    private final PlayerTideRepository    playerTideRepository;
    private final PlayerRepository        playerRepository;
    private final TideService             tideService;
    private final TelegramClient          telegramClient;
    private final PlatformTransactionManager txManager;

    private TransactionTemplate txTemplate;

    @PostConstruct
    void init() {
        txTemplate = new TransactionTemplate(txManager);
    }

    // ── Fishing done ──────────────────────────────────────────────────────

    @Scheduled(fixedDelay = 30_000)
    public void notifyFishingComplete() {
        // Фаза 1: короткая транзакция — отмечаем как уведомлённых, собираем данные
        record FishingNote(Long telegramId, String spotName) {}

        List<FishingNote> toNotify = txTemplate.execute(status -> {
            List<PlayerFishing> ready = playerFishingRepository.findFishingReady(LocalDateTime.now());
            ready.forEach(f -> f.setFishingNotified(true));
            playerFishingRepository.saveAll(ready);
            return ready.stream()
                    .map(f -> new FishingNote(
                            f.getPlayer().getTelegramId(),
                            f.getFishingSpot() != null
                                    ? f.getFishingSpot().getDisplayName().toLowerCase()
                                    : "у берега"))
                    .toList();
        });

        if (toNotify == null || toNotify.isEmpty()) return;

        // Фаза 2: вне транзакции — шлём API-запросы
        for (FishingNote note : toNotify) {
            sendMessage(note.telegramId(), FISHING_DONE_TEXT.formatted(note.spotName()));
        }
    }

    // ── Tide active ───────────────────────────────────────────────────────

    @Scheduled(fixedDelay = 60_000)
    public void notifyTideActive() {
        record TideNote(Long telegramId) {}

        List<TideNote> toNotify = txTemplate.execute(status -> {
            List<PlayerTide> active = playerTideRepository.findActiveTidesNotNotified(LocalDateTime.now());
            active.forEach(t -> t.setTideNotified(true));
            playerTideRepository.saveAll(active);
            return active.stream()
                    .map(t -> new TideNote(t.getPlayer().getTelegramId()))
                    .toList();
        });

        if (toNotify == null || toNotify.isEmpty()) return;

        for (TideNote note : toNotify) {
            sendMessage(note.telegramId(), TIDE_TEXT);
        }
    }

    // ── Legacy migration ──────────────────────────────────────────────────

    /** Расписываем первый прилив для игроков, зарегистрированных до появления механики. */
    @Scheduled(fixedDelay = 300_000)
    public void scheduleFirstTidesForLegacyPlayers() {
        List<Long> playerIds = txTemplate.execute(status -> {
            return playerTideRepository.findByTideAvailableAtIsNull()
                    .stream()
                    .map(t -> t.getPlayer().getId())
                    .toList();
        });

        if (playerIds == null || playerIds.isEmpty()) return;

        for (Long playerId : playerIds) {
            txTemplate.execute(status -> {
                playerRepository.findById(playerId)
                        .ifPresent(tideService::scheduleFirstTide);
                return null;
            });
        }
    }

    // ── Helper ────────────────────────────────────────────────────────────

    private void sendMessage(Long telegramId, String text) {
        try {
            telegramClient.execute(SendMessage.builder()
                    .chatId(String.valueOf(telegramId))
                    .text(text)
                    .build());
        } catch (TelegramApiException e) {
            log.error("Failed to send notification to player {}: {}", telegramId, e.getMessage());
        }
    }
}
