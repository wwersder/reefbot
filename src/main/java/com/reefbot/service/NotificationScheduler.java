package com.reefbot.service;

import com.reefbot.entity.Player;
import com.reefbot.entity.PlayerFishing;
import com.reefbot.entity.PlayerForest;
import com.reefbot.entity.PlayerMine;
import com.reefbot.entity.PlayerTide;
import com.reefbot.repository.PlayerFishingRepository;
import com.reefbot.repository.PlayerForestRepository;
import com.reefbot.repository.PlayerMineRepository;
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

    private static final String FOREST_DONE_TEXT = """
            🏹 Охота завершена!

            Добыча ждёт тебя — заходи в Лес и забирай.
            """;

    private static final String MINE_DONE_TEXT = """
            ⛰ Добыча завершена!

            Шахта ждёт — заходи в Холмы и забирай камень.
            """;

    private final PlayerFishingRepository playerFishingRepository;
    private final PlayerForestRepository  playerForestRepository;
    private final PlayerMineRepository    playerMineRepository;
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
        // Phase 1: short TX — mark notified, collect data
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

        // Phase 2: outside TX — send API requests
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

    // ── Forest done ───────────────────────────────────────────────────────

    @Scheduled(fixedDelay = 30_000)
    public void notifyForestComplete() {
        record ForestNote(Long telegramId) {}

        List<ForestNote> toNotify = txTemplate.execute(status -> {
            List<PlayerForest> ready = playerForestRepository.findForestReady(LocalDateTime.now());
            ready.forEach(f -> f.setNotified(true));
            playerForestRepository.saveAll(ready);
            return ready.stream()
                    .map(f -> new ForestNote(f.getPlayer().getTelegramId()))
                    .toList();
        });

        if (toNotify == null || toNotify.isEmpty()) return;
        for (ForestNote note : toNotify) {
            sendMessage(note.telegramId(), FOREST_DONE_TEXT);
        }
    }

    // ── Mine done ─────────────────────────────────────────────────────────

    @Scheduled(fixedDelay = 30_000)
    public void notifyMineComplete() {
        record MineNote(Long telegramId) {}

        List<MineNote> toNotify = txTemplate.execute(status -> {
            List<PlayerMine> ready = playerMineRepository.findMineReady(LocalDateTime.now());
            ready.forEach(m -> m.setNotified(true));
            playerMineRepository.saveAll(ready);
            return ready.stream()
                    .map(m -> new MineNote(m.getPlayer().getTelegramId()))
                    .toList();
        });

        if (toNotify == null || toNotify.isEmpty()) return;
        for (MineNote note : toNotify) {
            sendMessage(note.telegramId(), MINE_DONE_TEXT);
        }
    }

    // ── Tide recovery: reschedule expired tides ───────────────────────────

    /**
     * Reschedules tides that expired without the player interacting with them.
     * This happens when a player ignores the tide window (no BTN_TIDE press)
     * or exits the tide screen via Back before guessing — neither path calls scheduleNextTide().
     * Detected by: tideExpiresAt in the past AND tideAvailableAt in the past
     * (if scheduled, tideAvailableAt would be 5–10 h in the future).
     */
    @Scheduled(fixedDelay = 300_000)
    public void rescheduleExpiredTides() {
        List<Long> playerIds = txTemplate.execute(status ->
                playerTideRepository.findExpiredWithoutNextTide(LocalDateTime.now())
                        .stream()
                        .map(t -> t.getPlayer().getId())
                        .toList());

        if (playerIds == null || playerIds.isEmpty()) return;

        for (Long playerId : playerIds) {
            txTemplate.execute(status -> {
                playerRepository.findById(playerId).ifPresent(p -> {
                    tideService.scheduleNextTide(p);
                    playerRepository.save(p);
                    log.info("Rescheduled expired tide for player {}", p.getId());
                });
                return null;
            });
        }
    }

    // ── Legacy migration: first tide for pre-tide players ─────────────────

    /** Schedules the first tide for players registered before the tide mechanic existed. */
    @Scheduled(fixedDelay = 300_000)
    public void scheduleFirstTidesForLegacyPlayers() {
        List<Long> playerIds = txTemplate.execute(status ->
                playerTideRepository.findByTideAvailableAtIsNull()
                        .stream()
                        .map(t -> t.getPlayer().getId())
                        .toList());

        if (playerIds == null || playerIds.isEmpty()) return;

        for (Long playerId : playerIds) {
            txTemplate.execute(status -> {
                playerRepository.findById(playerId)
                        .ifPresent(tideService::scheduleFirstTide);
                return null;
            });
        }
    }

    // ── Legacy migration: forest + mine rows for pre-V29 players ─────────

    /**
     * Creates missing PlayerForest / PlayerMine rows for players registered before V29.
     * Runs every 5 minutes; once all rows exist the queries return nothing and this is free.
     */
    @Scheduled(fixedDelay = 300_000)
    public void backfillForestAndMineForLegacyPlayers() {
        List<Long> missingForest = txTemplate.execute(status ->
                playerRepository.findPlayersWithoutForest()
                        .stream().map(Player::getId).toList());

        if (missingForest != null) {
            for (Long playerId : missingForest) {
                txTemplate.execute(status -> {
                    playerRepository.findById(playerId).ifPresent(p -> {
                        PlayerForest forest = PlayerForest.builder().build();
                        forest.setPlayer(p);
                        p.setForest(forest);
                        playerRepository.save(p);
                        log.info("Created PlayerForest for legacy player {}", playerId);
                    });
                    return null;
                });
            }
        }

        List<Long> missingMine = txTemplate.execute(status ->
                playerRepository.findPlayersWithoutMine()
                        .stream().map(Player::getId).toList());

        if (missingMine != null) {
            for (Long playerId : missingMine) {
                txTemplate.execute(status -> {
                    playerRepository.findById(playerId).ifPresent(p -> {
                        PlayerMine mine = PlayerMine.builder().build();
                        mine.setPlayer(p);
                        p.setMine(mine);
                        playerRepository.save(p);
                        log.info("Created PlayerMine for legacy player {}", playerId);
                    });
                    return null;
                });
            }
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
