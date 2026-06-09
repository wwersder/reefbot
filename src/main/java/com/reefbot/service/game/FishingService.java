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

    // Index = level-1, value = cumulative XP needed to reach that level
    // Level 10 is max; Integer.MAX_VALUE is a guard so levelForXp never exceeds 10
    private static final int[] XP_THRESHOLDS = {0, 50, 150, 350, 700, 1200, 2000, 3500, 6000, 10000, Integer.MAX_VALUE};

    private static final String[] LEVEL_NAMES = {
            "🪣 Любитель",     // 1
            "🎣 Рыбак",        // 2
            "🐟 Бывалый",      // 3
            "🐠 Знаток рифов", // 4
            "🦀 Охотник",      // 5
            "⚓ Морской волк",  // 6
            "🐬 Друг океана",  // 7
            "🦈 Капитан глубин", // 8
            "🌊 Повелитель морей", // 9
            "🔱 Легенда архипелага" // 10
    };

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
        int oldLevel = player.getFishing().getFishingLevel();

        // Apply level bonuses
        int minFishBonus = oldLevel >= 2 ? 1 : 0;
        int maxFishBonus = oldLevel >= 8 ? 2 : 0;
        int effectiveMin = spot.getMinFish() + minFishBonus;
        int effectiveMax = spot.getMaxFish() + maxFishBonus;
        int fish = effectiveMin + random.nextInt(effectiveMax - effectiveMin + 1);

        int xpMultiplierPct = oldLevel >= 7 ? 130 : (oldLevel >= 4 ? 110 : 100);
        int xpEarned = spot.getXpReward() * xpMultiplierPct / 100;

        int effectiveBonusChance = spot.getBonusChance() + (oldLevel >= 5 ? 20 : 0);
        ResourceType bonusType = null;
        int bonusAmount = 0;
        if (spot.getBonusResource() != null && random.nextInt(100) < effectiveBonusChance) {
            bonusType = spot.getBonusResource();
            bonusAmount = 1;
        }

        resourceService.give(island, ResourceType.FISH, fish);
        if (bonusType != null) {
            resourceService.give(island, bonusType, bonusAmount);
        }
        islandRepository.save(island);

        int totalXp = player.getFishing().getFishingXp() + xpEarned;
        int newLevel = levelForXp(totalXp);

        boolean wasFirst = !Boolean.TRUE.equals(player.getState().getHasCompletedFirstFish());

        player.getFishing().setFishingXp(totalXp);
        player.getFishing().setFishingLevel(newLevel);
        player.getFishing().setFishingFinishAt(null);
        player.getFishing().setFishingSpot(null);
        player.getState().setHasCompletedFirstFish(true);
        playerRepository.save(player);

        return new FishingResult(spot, fish, bonusType, bonusAmount, xpEarned, totalXp, oldLevel, newLevel, wasFirst);
    }

    public int xpForNextLevel(int level) {
        if (level >= XP_THRESHOLDS.length - 1) return 0;
        return XP_THRESHOLDS[level];
    }

    public static String levelName(int level) {
        int idx = Math.max(0, Math.min(level - 1, LEVEL_NAMES.length - 1));
        return LEVEL_NAMES[idx];
    }

    /** What unlocks / improves at this level. Null = generic encouragement. */
    public static String levelUnlockText(int level) {
        return switch (level) {
            case 2 -> "+1 к минимальному улову на всех местах — каждая поездка стала чуть выгоднее.";
            case 3 -> "🔓 Открыто новое место: 🌊 В море\nДальше от берега — крупнее улов.";
            case 4 -> "+10% XP за каждую рыбалку — прокачка пойдёт быстрее.";
            case 5 -> "+20% шанс бонусного ресурса на всех местах.";
            case 6 -> "🔮 Открыт новый ресурс: Жемчуг\nТеперь у рифа и в море можно найти жемчуг.";
            case 7 -> "+30% XP за рыбалку — поздние уровни стали ближе.";
            case 8 -> "+2 к максимальному улову на всех местах.";
            case 9 -> "🎣🎣 Открыта вторая удочка!\nТеперь можно забросить удочку сразу в два места.";
            case 10 -> "✨ Легендарный улов разблокирован.\nПри каждой рыбалке есть шанс поймать редкий артефакт.";
            default -> null;
        };
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
