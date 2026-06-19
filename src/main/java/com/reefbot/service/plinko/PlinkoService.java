package com.reefbot.service.plinko;

import com.reefbot.config.TelegramProperties;
import com.reefbot.dto.plinko.*;
import com.reefbot.entity.Island;
import com.reefbot.entity.Player;
import com.reefbot.entity.PlinkoLog;
import com.reefbot.enums.PlayerStatus;
import com.reefbot.enums.PlinkoRisk;
import com.reefbot.repository.IslandRepository;
import com.reefbot.repository.PlayerRepository;
import com.reefbot.repository.PlinkoLogRepository;
import com.reefbot.util.TelegramInitDataVerifier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class PlinkoService {

    // ── Constants ─────────────────────────────────────────────────────────────

    public static final int DAILY_LOSS_LIMIT = 2000;
    public static final int MIN_BET          = 5;
    public static final int MAX_BET          = 500;
    public static final int JACKPOT_THRESHOLD_X = 15; // multiplier to trigger notification
    public static final long COOLDOWN_MILLIS = 1_000;

    /** Valid bet amounts (quick-select buttons). */
    public static final int[] BET_STEPS = {5, 10, 25, 50, 100, 250, 500};

    /**
     * Multiplier tables [risk][slot].
     * 8 rows → 9 slots (indices 0..8).
     */
    private static final double[][] MULT_8_LOW = {
            {1.5, 1.2, 1.1, 1.0, 0.5, 1.0, 1.1, 1.2, 1.5}
    };
    private static final double[][] MULT_8_MEDIUM = {
            {6.0, 2.5, 1.3, 0.8, 0.4, 0.8, 1.3, 2.5, 6.0}
    };
    private static final double[][] MULT_8_HIGH = {
            {18.0, 4.0, 1.3, 0.5, 0.1, 0.5, 1.3, 4.0, 18.0}
    };

    /**
     * 12 rows → 13 slots (indices 0..12). Only MEDIUM defined.
     */
    private static final double[][] MULT_12_MEDIUM = {
            {20.0, 7.0, 2.5, 1.5, 1.0, 0.7, 0.5, 0.7, 1.0, 1.5, 2.5, 7.0, 20.0}
    };

    private final SecureRandom secureRandom = new SecureRandom();
    private final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    // ── Dependencies ──────────────────────────────────────────────────────────

    private final PlayerRepository    playerRepository;
    private final IslandRepository    islandRepository;
    private final PlinkoLogRepository plinkoLogRepository;
    private final TelegramClient      telegramClient;
    private final TelegramProperties  telegramProperties;

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Verifies Telegram initData, then returns state for the Mini App.
     */
    @Transactional(readOnly = true)
    public PlinkoStateResponse getState(String rawInitData) {
        Player player = resolvePlayer(rawInitData);
        if (!isOnboarded(player)) {
            return PlinkoStateResponse.blocked();
        }

        Island island = player.getIsland();
        int balance   = island != null ? island.getShells() : 0;
        int dailyLost = currentDailyLost(player);
        int level     = player.getFishing() != null ? player.getFishing().getFishingLevel() : 1;

        return PlinkoStateResponse.ok(balance, dailyLost, level);
    }

    /**
     * Executes a plinko throw server-side and returns the result.
     */
    @Transactional
    public PlinkoPlayResponse play(String rawInitData, PlinkoPlayRequest req) {
        Player player = resolvePlayer(rawInitData);

        if (!isOnboarded(player)) {
            return PlinkoPlayResponse.error("ONBOARDING_REQUIRED",
                    "Завершите онбординг перед игрой.");
        }

        // ── Validate rows ────────────────────────────────────────────────────
        if (req.rows() != 8 && req.rows() != 12) {
            return PlinkoPlayResponse.error("INVALID_BET", "rows должен быть 8 или 12.");
        }
        if (req.rows() == 12) {
            int level = player.getFishing() != null ? player.getFishing().getFishingLevel() : 1;
            if (level < 5) {
                return PlinkoPlayResponse.error("ROWS_LOCKED",
                        "Доска 12 рядов открывается при уровне рыбака 5+.");
            }
        }

        // ── Validate bet ─────────────────────────────────────────────────────
        if (req.bet() < MIN_BET || req.bet() > MAX_BET) {
            return PlinkoPlayResponse.error("INVALID_BET",
                    "Ставка должна быть от " + MIN_BET + " до " + MAX_BET + " 🐚.");
        }

        Island island = player.getIsland();
        if (island == null || island.getShells() < req.bet()) {
            return PlinkoPlayResponse.error("INSUFFICIENT_BALANCE",
                    "Недостаточно ракушек. Нужно " + req.bet() + " 🐚.");
        }

        // ── Daily limit ──────────────────────────────────────────────────────
        int dailyLost = currentDailyLost(player);
        if (dailyLost >= DAILY_LOSS_LIMIT) {
            return PlinkoPlayResponse.error("DAILY_LIMIT_REACHED",
                    "Дневной лимит потерь достигнут. Возвращайся завтра! 🌅");
        }

        // ── Cooldown ─────────────────────────────────────────────────────────
        if (player.getPlinkoLastPlay() != null) {
            long elapsed = System.currentTimeMillis()
                    - player.getPlinkoLastPlay().atZone(java.time.ZoneId.systemDefault())
                          .toInstant().toEpochMilli();
            if (elapsed < COOLDOWN_MILLIS) {
                return PlinkoPlayResponse.error("COOLDOWN",
                        "Подожди немного перед следующим броском.");
            }
        }

        // ── Server-side RNG ──────────────────────────────────────────────────
        boolean[] path = new boolean[req.rows()];
        int slot = 0;
        for (int i = 0; i < req.rows(); i++) {
            path[i] = secureRandom.nextBoolean();
            if (path[i]) slot++;
        }

        double multiplier = getMultiplier(req.rows(), req.risk(), slot);
        int won    = (int) (req.bet() * multiplier);
        int profit = won - req.bet();

        // ── Apply to island balance ──────────────────────────────────────────
        island.setShells(island.getShells() - req.bet() + won);
        islandRepository.save(island);

        // ── Update player plinko state ───────────────────────────────────────
        LocalDate today = LocalDate.now();
        if (!today.equals(player.getPlinkoDailyDate())) {
            player.setPlinkoDailyLost(0);
            player.setPlinkoDailyDate(today);
        }
        if (profit < 0) {
            player.setPlinkoDailyLost(player.getPlinkoDailyLost() + (-profit));
        }
        player.setPlinkoLastPlay(LocalDateTime.now());
        playerRepository.save(player);

        // ── Log ──────────────────────────────────────────────────────────────
        PlinkoLog logEntry = PlinkoLog.builder()
                .playerId(player.getId())
                .islandId(island.getId())
                .bet(req.bet())
                .slot(slot)
                .multiplier(multiplier)
                .won(won)
                .boardRows(req.rows())
                .risk(req.risk())
                .playedAt(LocalDateTime.now())
                .build();
        plinkoLogRepository.save(logEntry);

        // ── Jackpot notification ─────────────────────────────────────────────
        if (multiplier >= JACKPOT_THRESHOLD_X) {
            sendJackpotNotification(player, won, multiplier, req.bet());
        }

        return PlinkoPlayResponse.success(slot, multiplier, won, profit, island.getShells(), path);
    }

    /**
     * Returns leaderboard (cached at HTTP layer via ResponseEntity or just plain).
     */
    @Transactional(readOnly = true)
    public PlinkoLeaderboardResponse getLeaderboard() {
        List<PlinkoLog> topWinLogs        = plinkoLogRepository.findTopByProfit();
        List<PlinkoLog> topMultiplierLogs = plinkoLogRepository.findTopByMultiplier();

        return new PlinkoLeaderboardResponse(
                topWinLogs.stream().map(this::toEntry).toList(),
                topMultiplierLogs.stream().map(this::toEntry).toList()
        );
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Player resolvePlayer(String rawInitData) {
        Map<String, String> params = TelegramInitDataVerifier.verify(
                rawInitData, telegramProperties.getToken());
        Long telegramId = TelegramInitDataVerifier.extractTelegramId(params);

        return playerRepository.findByTelegramId(telegramId)
                .orElseThrow(() -> new SecurityException("Player not found for tgId=" + telegramId));
    }

    private boolean isOnboarded(Player player) {
        return player.getStatus() == PlayerStatus.ACTIVE
                && player.getOnboardingStep() == null;
    }

    private int currentDailyLost(Player player) {
        if (player.getPlinkoDailyDate() == null) return 0;
        if (!LocalDate.now().equals(player.getPlinkoDailyDate())) return 0;
        return player.getPlinkoDailyLost() != null ? player.getPlinkoDailyLost() : 0;
    }

    private double getMultiplier(int rows, PlinkoRisk risk, int slot) {
        double[] table;
        if (rows == 12) {
            table = MULT_12_MEDIUM[0];
        } else {
            table = switch (risk) {
                case LOW    -> MULT_8_LOW[0];
                case MEDIUM -> MULT_8_MEDIUM[0];
                case HIGH   -> MULT_8_HIGH[0];
            };
        }
        return table[Math.min(slot, table.length - 1)];
    }

    private void sendJackpotNotification(Player player, int won, double multiplier, int bet) {
        String display = player.getUsername() != null ? "@" + player.getUsername() : "Игрок";
        String text = String.format(
                "🎰 <b>Джекпот!</b> %s поймал ×%.0f в Reef Plinko!\n" +
                "Ставка: %d 🐚 → Выигрыш: <b>%d 🐚</b>",
                display, multiplier, bet, won);
        try {
            telegramClient.execute(SendMessage.builder()
                    .chatId(player.getTelegramId())
                    .text(text)
                    .parseMode("HTML")
                    .build());
        } catch (TelegramApiException e) {
            log.warn("Could not send jackpot notification to player {}", player.getId(), e);
        }
    }

    private PlinkoLeaderboardResponse.LeaderboardEntry toEntry(PlinkoLog l) {
        // Resolve username from player_id lazily — leaderboard is cached, acceptable
        Optional<Player> p = playerRepository.findById(l.getPlayerId());
        String username = p.map(pl -> pl.getUsername() != null ? pl.getUsername() : "ID " + pl.getId())
                .orElse("?");
        return new PlinkoLeaderboardResponse.LeaderboardEntry(
                username,
                l.getWon() - l.getBet(),
                l.getWon(),
                l.getMultiplier(),
                l.getBet(),
                l.getPlayedAt().format(DATE_FMT)
        );
    }
}
