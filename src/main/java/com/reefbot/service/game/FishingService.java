package com.reefbot.service.game;

import com.reefbot.entity.Island;
import com.reefbot.entity.Player;
import com.reefbot.enums.FishingSpot;
import com.reefbot.enums.ResourceType;
import com.reefbot.repository.IslandRepository;
import com.reefbot.repository.PlayerRepository;
import com.reefbot.service.ResourceService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Random;

@Service
@RequiredArgsConstructor
public class FishingService {

    private static final int[] XP_THRESHOLDS = {0, 30, 100, 250, 500, Integer.MAX_VALUE};

    private final ResourceService resourceService;
    private final IslandRepository islandRepository;
    private final PlayerRepository playerRepository;
    private final Random random = new Random();

    public boolean isActive(Player player) {
        LocalDateTime finishAt = player.getFishing().getFishingFinishAt();
        return finishAt != null && LocalDateTime.now().isBefore(finishAt);
    }

    public boolean isReady(Player player) {
        LocalDateTime finishAt = player.getFishing().getFishingFinishAt();
        return finishAt != null && !LocalDateTime.now().isBefore(finishAt);
    }

    /** Human-readable remaining time, e.g. "8 мин 42 сек" or "45 сек". */
    public String timeRemainingText(Player player) {
        if (!isActive(player)) return "0 сек";
        long totalSeconds = java.time.Duration.between(
                LocalDateTime.now(), player.getFishing().getFishingFinishAt()).getSeconds();
        long minutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;
        if (minutes > 0) return minutes + " мин " + seconds + " сек";
        return seconds + " сек";
    }

    public void startFishing(Player player, FishingSpot spot) {
        player.getFishing().setFishingSpot(spot);
        player.getFishing().setFishingFinishAt(LocalDateTime.now().plusMinutes(spot.getDurationMinutes()));
        player.getFishing().setFishingNotified(false);
        playerRepository.save(player);
    }

    public FishingResult collectFish(Player player, Island island) {
        FishingSpot spot = player.getFishing().getFishingSpot();

        int fish = spot.getMinFish() + random.nextInt(spot.getMaxFish() - spot.getMinFish() + 1);

        ResourceType bonusType = null;
        int bonusAmount = 0;
        if (spot.getBonusResource() != null && random.nextInt(100) < spot.getBonusChance()) {
            bonusType = spot.getBonusResource();
            bonusAmount = 1;
        }

        resourceService.give(island, ResourceType.FISH, fish);
        if (bonusType != null) {
            resourceService.give(island, bonusType, bonusAmount);
        }
        islandRepository.save(island);

        int xpEarned = spot.getXpReward();
        int totalXp = player.getFishing().getFishingXp() + xpEarned;
        int newLevel = levelForXp(totalXp);

        boolean wasFirst = !Boolean.TRUE.equals(player.getState().getHasCompletedFirstFish());

        player.getFishing().setFishingXp(totalXp);
        player.getFishing().setFishingLevel(newLevel);
        player.getFishing().setFishingFinishAt(null);
        player.getFishing().setFishingSpot(null);
        player.getState().setHasCompletedFirstFish(true);
        playerRepository.save(player);

        return new FishingResult(spot, fish, bonusType, bonusAmount, xpEarned, totalXp, newLevel, wasFirst);
    }

    public int xpForNextLevel(int level) {
        if (level >= XP_THRESHOLDS.length - 1) return 0;
        return XP_THRESHOLDS[level];
    }

    private int levelForXp(int xp) {
        int level = 1;
        for (int i = 1; i < XP_THRESHOLDS.length; i++) {
            if (xp >= XP_THRESHOLDS[i]) level = i + 1;
            else break;
        }
        return level;
    }
}
