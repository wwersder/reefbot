package com.reefbot.service.game;

import com.reefbot.entity.Island;
import com.reefbot.entity.Player;
import com.reefbot.repository.IslandRepository;
import com.reefbot.repository.PlayerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

/**
 * Daily bonus logic: once per 20 hours (not strict midnight — avoids timezone issues).
 * Streak: consecutive claims → bigger rewards. Streak breaks if skipped > 48h.
 */
@Service
@RequiredArgsConstructor
public class DailyBonusService {

    /** Minimum gap between claims: 20 hours. */
    private static final int CLAIM_COOLDOWN_HOURS = 20;

    /** Streak breaks if last claim was > 48 hours ago. */
    private static final int STREAK_BREAK_HOURS = 48;

    private final IslandRepository islandRepository;
    private final PlayerRepository playerRepository;

    public boolean canClaim(Player player) {
        LocalDateTime last = player.getState().getDailyBonusAt();
        if (last == null) return true;
        return Duration.between(last, LocalDateTime.now()).toHours() >= CLAIM_COOLDOWN_HOURS;
    }

    public LocalDateTime nextClaimAt(Player player) {
        LocalDateTime last = player.getState().getDailyBonusAt();
        if (last == null) return LocalDateTime.now();
        return last.plusHours(CLAIM_COOLDOWN_HOURS);
    }

    public long hoursUntilNextClaim(Player player) {
        return Math.max(0, Duration.between(LocalDateTime.now(), nextClaimAt(player)).toHours() + 1);
    }

    public DailyBonusResult claim(Player player, Island island) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime last = player.getState().getDailyBonusAt();

        // Calculate new streak
        int streak = player.getState().getDailyStreak();
        if (last == null) {
            streak = 1;
        } else {
            long hoursSinceLast = ChronoUnit.HOURS.between(last, now);
            if (hoursSinceLast > STREAK_BREAK_HOURS) {
                streak = 1; // streak broken
            } else {
                streak = Math.min(streak + 1, 7); // cap at 7 for display
            }
        }

        // Calculate reward based on streak (day 1 = base, day 7 = 3x)
        double multiplier = 1.0 + (streak - 1) * 0.35;
        int baseShells = 10;
        int baseFish   = 5;
        int shellsReward = (int) Math.round(baseShells * multiplier);
        int fishReward   = (int) Math.round(baseFish   * multiplier);

        // Apply (respecting storage cap)
        int storageLeft = island.getStorageCapacity() - totalStored(island);
        int shellsActual = Math.min(shellsReward, storageLeft);
        int fishActual   = Math.min(fishReward, Math.max(0, storageLeft - shellsActual));

        island.setShells(island.getShells() + shellsActual);
        island.setFish(island.getFish() + fishActual);
        islandRepository.save(island);

        player.getState().setDailyBonusAt(now);
        player.getState().setDailyStreak(streak);
        playerRepository.save(player);

        return new DailyBonusResult(shellsActual, fishActual, streak);
    }

    private static int totalStored(Island island) {
        return island.getWood() + island.getStone() + island.getFish()
                + island.getShells() + island.getCoral();
    }

    public record DailyBonusResult(int shellsGained, int fishGained, int streak) {}
}
