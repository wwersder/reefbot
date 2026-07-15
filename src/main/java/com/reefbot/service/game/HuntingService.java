package com.reefbot.service.game;

import com.reefbot.entity.Island;
import com.reefbot.entity.Player;
import com.reefbot.entity.PlayerForest;
import com.reefbot.enums.HuntingSpot;
import com.reefbot.repository.IslandRepository;
import com.reefbot.repository.PlayerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Random;

@Service
@RequiredArgsConstructor
public class HuntingService {

    // XP thresholds: index = level-1, value = cumulative XP to reach that level
    private static final int[] XP_THRESHOLDS = {
            0, 100, 350, 850, 1750, 3250, 5750, 9750, 15950, 25450, Integer.MAX_VALUE
    };

    private static final String[] LEVEL_NAMES = {
            "🗡 Новичок",       // 1
            "🏹 Следопыт",      // 2
            "🐾 Охотник",       // 3
            "🦌 Загонщик",      // 4
            "🌿 Лесной страж",  // 5
            "🐺 Волк",          // 6
            "🪃 Ловчий",        // 7
            "🦅 Сокольничий",   // 8
            "🌑 Тень леса",     // 9
            "🌟 Мастер охоты"   // 10
    };

    // ── Random events per spot ───────────────────────────────────────────────

    public record HuntEvent(String text, int meatDelta, int furDelta, int xpDelta) {}

    private static final List<HuntEvent> EVENTS_EDGE = List.of(
            new HuntEvent("🐿 Белка разбудила птиц — дичь ушла глубже. Пришлось довольствоваться малым.", -1, 0, 0),
            new HuntEvent("🍄 По пути нашёл следы зверя — опыт охотника растёт.", 0, 0, 8),
            new HuntEvent("🐇 Повезло: кролик выскочил прямо под руку.", 2, 0, 0),
            new HuntEvent("🌧 Дождь смыл следы — пришлось возвращаться раньше.", -1, 0, 0)
    );

    private static final List<HuntEvent> EVENTS_DEEP = List.of(
            new HuntEvent("🦌 Встретил оленя — удача! Добыча богаче обычного.", 3, 0, 10),
            new HuntEvent("🐗 Кабан прогнал с охотничьей тропы — добыча меньше.", -2, 0, 0),
            new HuntEvent("🪶 Нашёл перья редкой птицы — ценный трофей.", 0, 1, 12),
            new HuntEvent("🌿 Густой подлесок замедлил охоту, но метка оказалась верной.", 1, 0, 5),
            new HuntEvent("⚡ Внезапный шквал спугнул дичь.", -2, 0, 0)
    );

    private static final List<HuntEvent> EVENTS_WILD = List.of(
            new HuntEvent("🐺 Следы волчьей стаи — добыча нетронута, но сам поспешил уйти.", -3, 0, 0),
            new HuntEvent("🦅 Орёл показал путь к лёжке. Улов отменный.", 4, 1, 15),
            new HuntEvent("🌀 Буря налетела внезапно — пришлось укрыться, но нашёл старую нору.", 0, 2, 10),
            new HuntEvent("🌑 В урочище тихо. Словно лес сам подаёт добычу.", 5, 0, 20),
            new HuntEvent("🐍 Встреча с гадюкой — отступил. Добыча поменьше.", -3, -1, 0)
    );

    private static final int EDGE_EVENT_CHANCE = 20;
    private static final int DEEP_EVENT_CHANCE = 25;
    private static final int WILD_EVENT_CHANCE = 30;

    // ── Narratives per spot ──────────────────────────────────────────────────

    private static final List<String> NARRATIVES_EDGE = List.of(
            "Опушка привычна. Ловушки расставлены, добыча поймана.",
            "Близко к лагерю, но и здесь есть что взять.",
            "Тихая охота у края леса. Эффективно и безопасно.",
            "Лёгкая прогулка с пустыми руками — и тяжёлый путь обратно."
    );

    private static final List<String> NARRATIVES_DEEP = List.of(
            "Чаща встретила тишиной и запахом хвои. Охота прошла удачно.",
            "Глубже в лес — богаче добыча. Проверено.",
            "Следы уводили всё дальше. Вернулся не с пустыми руками.",
            "Тёмный лес хранит свои дары. Умеешь искать — найдёшь."
    );

    private static final List<String> NARRATIVES_WILD = List.of(
            "Урочище не прощает слабости. Но отдаёт сполна тем, кто готов.",
            "Долгий путь, долгое ожидание. Результат говорит сам за себя.",
            "Глухомань встретила настороженно. Ушёл с богатой добычей.",
            "Дикое место. Звериные тропы, тишина и награда за терпение."
    );

    private final IslandRepository islandRepository;
    private final PlayerRepository playerRepository;
    private final Random random = new Random();

    // ── State checks ─────────────────────────────────────────────────────────

    public boolean isActive(Player player) {
        LocalDateTime finishAt = player.getForest().getFinishAt();
        return finishAt != null && LocalDateTime.now().isBefore(finishAt);
    }

    public boolean isReady(Player player) {
        LocalDateTime finishAt = player.getForest().getFinishAt();
        return finishAt != null && !LocalDateTime.now().isBefore(finishAt);
    }

    public boolean isIdle(Player player) {
        return player.getForest().getFinishAt() == null;
    }

    public String timeRemainingText(Player player) {
        if (!isActive(player)) return "0 сек";
        long total = Duration.between(LocalDateTime.now(), player.getForest().getFinishAt()).getSeconds();
        long hours   = total / 3600;
        long minutes = (total % 3600) / 60;
        long seconds = total % 60;
        if (hours > 0)   return hours + " ч " + minutes + " мин";
        if (minutes > 0) return minutes + " мин " + seconds + " сек";
        return seconds + " сек";
    }

    // ── Actions ──────────────────────────────────────────────────────────────

    public void startHunt(Player player, HuntingSpot spot) {
        int level = player.getForest().getHunterLevel();
        // Level 4: -5%; level 8: -15% cumulative
        double speedMultiplier = 1.0;
        if (level >= 8) speedMultiplier -= 0.15;
        else if (level >= 4) speedMultiplier -= 0.05;
        int minutes = (int) Math.round(spot.getBaseMinutes() * speedMultiplier);

        PlayerForest forest = player.getForest();
        forest.setHuntingSpot(spot);
        forest.setFinishAt(LocalDateTime.now().plusMinutes(Math.max(1, minutes)));
        forest.setNotified(false);
        playerRepository.save(player);
    }

    /**
     * Collect yield from a finished hunt.
     * Clears finishAt and huntingSpot; applies meat + fur to island; levels up hunter.
     */
    public HuntResult collectYield(Player player, Island island) {
        PlayerForest forest = player.getForest();
        HuntingSpot spot = forest.getHuntingSpot();
        if (spot == null) spot = HuntingSpot.EDGE;

        int level = forest.getHunterLevel();
        boolean legendary = level >= 10;

        // Meat: +1 at lv2, +2 at lv6 (cumulative); +30% max at lv10
        int meatBonus = (level >= 6 ? 2 : (level >= 2 ? 1 : 0));
        int meatMin = spot.getMeatMin() + meatBonus;
        int meatMax = spot.getMeatMax() + meatBonus + (legendary ? Math.max(1, (int)(spot.getMeatMax() * 0.3)) : 0);
        int meat = meatMin + random.nextInt(Math.max(1, meatMax - meatMin + 1));

        // Fur
        int fur = 0;
        if (spot.getFurMax() > 0) {
            int furMin = spot.getFurMin();
            int furMax = spot.getFurMax() + (legendary ? 1 : 0);
            fur = furMin + random.nextInt(Math.max(1, furMax - furMin + 1));
        }

        // XP: +10% at lv3, +25% at lv5, +50% at lv9
        int xpMultPct = level >= 9 ? 150 : (level >= 5 ? 125 : (level >= 3 ? 110 : 100));
        int xpEarned = (int) Math.round(spot.getXpReward() * xpMultPct / 100.0);

        // Random event
        HuntEvent event = rollEvent(spot);
        if (event != null) {
            meat     = Math.max(0, meat + event.meatDelta());
            fur      = Math.max(0, fur  + event.furDelta());
            xpEarned += event.xpDelta();
        }

        // Level 7: 15% chance of double fur
        boolean doubleFur = false;
        if (level >= 7 && fur > 0 && random.nextInt(100) < 15) {
            fur *= 2;
            doubleFur = true;
        }

        // Apply to island (respect storage cap)
        int storageLeft = island.getStorageCapacity() - totalStored(island);
        int meatActual  = Math.min(meat, Math.max(0, storageLeft));
        int furActual   = Math.min(fur,  Math.max(0, storageLeft - meatActual));

        island.setMeat(island.getMeat() + meatActual);
        island.setFur(island.getFur() + furActual);
        islandRepository.save(island);

        // Level up
        int oldLevel = forest.getHunterLevel();
        int totalXp  = forest.getHunterXp() + xpEarned;
        int newLevel  = levelForXp(totalXp);

        forest.setHunterXp(totalXp);
        forest.setHunterLevel(newLevel);
        forest.setHuntingSpot(null);
        forest.setFinishAt(null);
        playerRepository.save(player);

        return new HuntResult(spot, meatActual, furActual, xpEarned, totalXp,
                oldLevel, newLevel, pickNarrative(spot), event, doubleFur);
    }

    // ── Event helpers ─────────────────────────────────────────────────────────

    private HuntEvent rollEvent(HuntingSpot spot) {
        int chance = switch (spot) {
            case EDGE -> EDGE_EVENT_CHANCE;
            case DEEP -> DEEP_EVENT_CHANCE;
            case WILD -> WILD_EVENT_CHANCE;
        };
        if (random.nextInt(100) >= chance) return null;
        List<HuntEvent> pool = switch (spot) {
            case EDGE -> EVENTS_EDGE;
            case DEEP -> EVENTS_DEEP;
            case WILD -> EVENTS_WILD;
        };
        return pool.get(random.nextInt(pool.size()));
    }

    private static String pickNarrative(HuntingSpot spot) {
        List<String> pool = switch (spot) {
            case EDGE -> NARRATIVES_EDGE;
            case DEEP -> NARRATIVES_DEEP;
            case WILD -> NARRATIVES_WILD;
        };
        return pool.get((int) (Math.random() * pool.size()));
    }

    // ── XP / Level helpers ────────────────────────────────────────────────────

    public static String levelName(int level) {
        int idx = Math.max(0, Math.min(level - 1, LEVEL_NAMES.length - 1));
        return LEVEL_NAMES[idx];
    }

    public int xpForNextLevel(int level) {
        if (level >= XP_THRESHOLDS.length - 1) return 0;
        return XP_THRESHOLDS[level];
    }

    /** Cumulative XP threshold that started the given level (lower bound, exclusive). */
    public int xpForLevel(int level) {
        if (level <= 1) return 0;
        return XP_THRESHOLDS[Math.min(level - 1, XP_THRESHOLDS.length - 1)];
    }

    public int levelForXp(int xp) {
        int level = 1;
        for (int i = 1; i < XP_THRESHOLDS.length; i++) {
            if (xp >= XP_THRESHOLDS[i]) level = i + 1;
            else break;
        }
        return Math.min(level, 10);
    }

    public static String levelUnlockText(int level) {
        return switch (level) {
            case 2  -> "+1 к добыче мяса на всех угодьях.";
            case 3  -> "+10% XP за каждую охоту.";
            case 4  -> "⚡ Время охоты сокращается на 5%.";
            case 5  -> "+25% XP за каждую охоту.";
            case 6  -> "+2 к мясу на всех угодьях.";
            case 7  -> "🪶 15% шанс двойного меха.";
            case 8  -> "⚡ Время охоты сокращается ещё на 10%.";
            case 9  -> "+50% XP за каждую охоту.";
            case 10 -> "🌟 Легендарная добыча: +30% к максимальному улову.";
            default -> null;
        };
    }

    private static int totalStored(Island island) {
        return island.getWood() + island.getStone() + island.getFish()
                + island.getShells() + island.getCoral()
                + island.getMeat() + island.getFur();
    }

    // ── Result record ─────────────────────────────────────────────────────────

    public record HuntResult(
            HuntingSpot spot,
            int meatGained,
            int furGained,
            int xpEarned,
            int totalXp,
            int oldLevel,
            int newLevel,
            String narrative,
            HuntEvent event,
            boolean doubleFur
    ) {
        public boolean leveledUp() { return newLevel > oldLevel; }
        public boolean hasEvent()  { return event != null; }
    }
}
