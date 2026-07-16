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

    // ── Interactive event records ─────────────────────────────────────────────

    /** Outcome of a single event choice. */
    public record EventOutcome(int meatDelta, int furDelta, int xpDelta, String text) {}

    /**
     * An interactive event shown to the player during hunt collection.
     * Player picks choice A (risky) or B (safe) via inline buttons.
     */
    public record InteractiveHuntEvent(
            String narrative,
            String choiceALabel,
            String choiceBLabel,
            int successChanceA,
            EventOutcome outcomeASuccess,
            EventOutcome outcomeAFail,
            EventOutcome outcomeB
    ) {}

    /** Result of rolling an interactive event — carries the event index for callback routing. */
    public record RolledEvent(int index, InteractiveHuntEvent event) {}

    // ── Events per spot ──────────────────────────────────────────────────────

    private static final List<InteractiveHuntEvent> EVENTS_EDGE = List.of(
            new InteractiveHuntEvent(
                    "На тропе замер ёж — рядом с ним что-то торчит из земли.",
                    "Спугнуть и взять",
                    "Пройти мимо",
                    60,
                    new EventOutcome( 3,  1, 0, "Под ёжом оказалась заначка. Взял всё что было."),
                    new EventOutcome(-1,  0, 5, "Ёж напугал тебя самого. Дичь разбежалась."),
                    new EventOutcome( 1,  0, 5, "Обошёл стороной. Охота как охота.")
            ),
            new InteractiveHuntEvent(
                    "Свежие следы кролика — совсем рядом. Можно догнать.",
                    "Пойти по следу",
                    "Продолжить как есть",
                    50,
                    new EventOutcome( 5,  0, 0, "Кролик попался. Удача!"),
                    new EventOutcome( 0,  0, 10, "Ушёл в нору. Зато опыт — твой."),
                    new EventOutcome( 0,  0,  8, "Не стал отвлекаться. Охота прошла ровно.")
            ),
            new InteractiveHuntEvent(
                    "Поляна с грибами. Можно задержаться и поискать дичь рядом.",
                    "Задержаться, поискать",
                    "Взять грибы и уйти",
                    70,
                    new EventOutcome( 2,  0, 15, "Нашёл и грибы, и зверя. Двойная удача!"),
                    new EventOutcome( 0,  0,  5, "Гриб оказался несъедобным. Вернулся ни с чем."),
                    new EventOutcome( 0,  0, 12, "Грибы в корзине. Дичь в другой раз.")
            )
    );

    private static final List<InteractiveHuntEvent> EVENTS_DEEP = List.of(
            new InteractiveHuntEvent(
                    "Раненый олень медленно уходит от тебя. Стадо — совсем рядом.",
                    "Добить",
                    "Оставить стадо в покое",
                    65,
                    new EventOutcome( 8,  0,  0, "Удар точный. Богатая добыча."),
                    new EventOutcome( 2,  0,  0, "Стадо всполошилось. Взял только то что успел."),
                    new EventOutcome( 0,  1, 12, "Мудрое решение. Охотник думает наперёд.")
            ),
            new InteractiveHuntEvent(
                    "Свежий волчий след рядом с твоей тропой. Он охотился здесь недавно.",
                    "Устроить засаду у норы",
                    "Обойти стороной",
                    40,
                    new EventOutcome( 6,  2,  0, "Волк ушёл, но добычу оставил. Твоя."),
                    new EventOutcome(-2,  0,  0, "Волк почуял тебя. Пришлось отступить."),
                    new EventOutcome( 0,  0, 15, "Хороший охотник знает, когда отступить.")
            ),
            new InteractiveHuntEvent(
                    "В старом дубе — глубокое дупло. Внутри что-то шевелится.",
                    "Сунуть руку",
                    "Оставить",
                    55,
                    new EventOutcome( 0,  3,  0, "Целый меховой трофей. Повезло!"),
                    new EventOutcome( 0,  0,  8, "Пусто. Только заноза в пальце."),
                    new EventOutcome( 0,  1, 10, "Лес всегда даёт своё. Не торопись.")
            )
    );

    private static final List<InteractiveHuntEvent> EVENTS_WILD = List.of(
            new InteractiveHuntEvent(
                    "На поляне — свежие следы медведя. Он ушёл совсем недавно.",
                    "Выследить",
                    "Обойти поляну",
                    35,
                    new EventOutcome(12,  4,  0, "Вышел на медведя у ручья. Богатейшая добыча."),
                    new EventOutcome(-3,  0,  0, "Медведь учуял тебя. Пришлось бежать."),
                    new EventOutcome( 0,  2, 20, "Осторожность — мудрость охотника.")
            ),
            new InteractiveHuntEvent(
                    "Старый охотничий лагерь в глуши. Что-то блестит под брезентом.",
                    "Обыскать лагерь",
                    "Не трогать чужое",
                    60,
                    new EventOutcome( 0,  3, 25, "Нашёл старые запасы. Знание охотника бесценно."),
                    new EventOutcome( 0,  0, 10, "Лагерь пуст. Зато опыт — твой."),
                    new EventOutcome( 0,  0, 18, "Суеверия охотника. Иногда лучше не знать.")
            ),
            new InteractiveHuntEvent(
                    "Смеркается раньше обычного. Ночью добыча богаче, но и риск выше.",
                    "Остаться до рассвета",
                    "Вернуться пока светло",
                    50,
                    new EventOutcome(10,  3,  0, "Ночь принесла богатый улов."),
                    new EventOutcome( 4,  0,  0, "Ночь прошла спокойно, но холодно."),
                    new EventOutcome( 4,  0,  0, "Безопасный выбор. Домой засветло.")
            )
    );

    private static final int EDGE_EVENT_CHANCE = 20;
    private static final int DEEP_EVENT_CHANCE = 25;
    private static final int WILD_EVENT_CHANCE = 30;

    // ── Narratives per spot ──────────────────────────────────────────────────

    private static final List<String> NARRATIVES_EDGE = List.of(
            "Опушка привычна. Ловушки расставлены, добыча поймана.",
            "Близко к лагерю, но и здесь есть что взять.",
            "Тихая охота у края леса. Эффективно и безопасно.",
            "Лёгкая прогулка — и тяжёлый путь обратно с добычей."
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
     * Roll a deterministic interactive event for this hunt.
     * Uses {@code finishAt.toEpochSecond()} as seed — identical result on each call
     * for the same hunt session (needed for BTN_COLLECT re-entry and callback resolution).
     *
     * @return rolled event with its list index, or {@code null} if no event fires
     */
    public static RolledEvent rollInteractiveEvent(HuntingSpot spot, long seed) {
        Random rng = new Random(seed);
        int chance = switch (spot) {
            case EDGE -> EDGE_EVENT_CHANCE;
            case DEEP -> DEEP_EVENT_CHANCE;
            case WILD -> WILD_EVENT_CHANCE;
        };
        if (rng.nextInt(100) >= chance) return null;
        List<InteractiveHuntEvent> pool = eventPool(spot);
        int idx = rng.nextInt(pool.size());
        return new RolledEvent(idx, pool.get(idx));
    }

    /** Returns the interactive event pool for a spot (for use in callback handlers). */
    public static List<InteractiveHuntEvent> eventPool(HuntingSpot spot) {
        return switch (spot) {
            case EDGE -> EVENTS_EDGE;
            case DEEP -> EVENTS_DEEP;
            case WILD -> EVENTS_WILD;
        };
    }

    /**
     * Collect yield from a finished hunt, without external event modifiers.
     * Call this when no interactive event fired.
     */
    public HuntResult collectYield(Player player, Island island) {
        return collectYield(player, island, 0, 0, 0);
    }

    /**
     * Collect yield with additional modifiers from an interactive event outcome.
     * Does NOT roll internal events — caller is responsible for resolving the event.
     *
     * @param extraMeat additional meat from event (can be negative)
     * @param extraFur  additional fur from event (can be negative)
     * @param extraXp   additional XP from event
     */
    public HuntResult collectYield(Player player, Island island,
                                   int extraMeat, int extraFur, int extraXp) {
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

        // Apply event modifiers
        meat     = Math.max(0, meat     + extraMeat);
        fur      = Math.max(0, fur      + extraFur);
        xpEarned = Math.max(0, xpEarned + extraXp);

        // Roll trap (if player placed one during a beast sighting)
        String trapNote = null;
        Integer trapIdx = forest.getSightingTrapChoice();
        if (trapIdx != null) {
            ForestEventService.TrapOption trap = ForestEventService.trapPool(spot).get(trapIdx);
            if (random.nextInt(100) < trap.chance()) {
                meat    += trap.meatBonus();
                fur     += trap.furBonus();
                trapNote = "🪤 Ловушка сработала" + trap.emoji() + " " + trap.locationName() + "!"
                        + (trap.meatBonus() > 0 ? " +" + trap.meatBonus() + " 🥩" : "")
                        + (trap.furBonus()  > 0 ? " +" + trap.furBonus()  + " 🪶" : "");
            } else {
                trapNote = "🪤 " + trap.emoji() + " Ловушка пустая — зверь обошёл стороной.";
            }
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
        // Clear sighting window — it belongs to this hunt session only
        forest.setSightingAvailableAt(null);
        forest.setSightingExpiresAt(null);
        forest.setSightingClaimed(false);
        forest.setSightingNotified(false);
        forest.setSightingTrapChoice(null);
        playerRepository.save(player);

        return new HuntResult(spot, meatActual, furActual, xpEarned, totalXp,
                oldLevel, newLevel, pickNarrative(spot), doubleFur, trapNote);
    }

    // ── Narrative helpers ─────────────────────────────────────────────────────

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

    /** Cumulative XP threshold that started the given level (lower bound). */
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
            boolean doubleFur,
            /** Null if no trap was set; otherwise e.g. "🪤 Ловушка сработала! +6 🥩" */
            String trapNote
    ) {
        public boolean leveledUp() { return newLevel > oldLevel; }
        public boolean hasTrap()   { return trapNote != null; }
    }
}
