package com.reefbot.service.game;

import com.reefbot.entity.Island;
import com.reefbot.entity.Player;
import com.reefbot.entity.PlayerForest;
import com.reefbot.enums.ForestActivity;
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
public class ForestService {

    // XP thresholds: index = level-1, value = cumulative XP needed to reach that level
    private static final int[] XP_THRESHOLDS = {0, 80, 200, 450, 900, 1600, 2800, 4500, 7000, 10000, Integer.MAX_VALUE};

    private static final String[] LEVEL_NAMES = {
            "🪓 Новичок",        // 1
            "🌿 Лесоруб",        // 2
            "🌲 Бывалый",        // 3
            "🦉 Следопыт",       // 4
            "🪵 Мастер",         // 5
            "🌳 Хранитель леса", // 6
            "🍄 Грибник",        // 7
            "🦌 Лесной дух",     // 8
            "🌑 Тень чащи",      // 9
            "🌟 Легенда леса"    // 10
    };

    // ── Random event pools per activity ──────────────────────────────────────

    // Each entry: [emoji+text, woodDelta, shellsDelta, xpDelta]
    // woodDelta/shellsDelta in absolute units; negative = loss; xpDelta = bonus XP
    private static final List<ForestEvent> EVENTS_CHOP_NEAR = List.of(
            new ForestEvent("🐿 Белка сбросила с ветки пару ракушек — взял заодно.", 0, 2, 0),
            new ForestEvent("💨 Налетел шквал, разметал часть заготовленных дров.", -2, 0, 0),
            new ForestEvent("🍯 Нашёл дупло с диковинками — ракушка и мёд!", 0, 2, 5),
            new ForestEvent("🐦 Пара дроздов обронила что-то блестящее у корней.", 0, 1, 0)
    );

    private static final List<ForestEvent> EVENTS_CHOP_FAR = List.of(
            new ForestEvent("💎 В трухлявом пне нашлась старая ракушка.", 0, 2, 0),
            new ForestEvent("🦌 Олень напугал — пришлось отступить чуть раньше.", -3, 0, 0),
            new ForestEvent("🍄 Боровики по пути — собрал на ходу.", 0, 3, 8),
            new ForestEvent("🌧 Застал ливень — дрова отсырели.", -2, 0, 0),
            new ForestEvent("✨ На обратном пути нашлась упавшая ветка особого дерева.", 2, 0, 10)
    );

    private static final List<ForestEvent> EVENTS_GATHER = List.of(
            new ForestEvent("🌺 Редкий цветок! Не тронул, зато опыт за наблюдение.", 0, 0, 12),
            new ForestEvent("🐍 Змея зашипела — выронил часть корзины.", 0, -2, 0),
            new ForestEvent("✨ Под листвой оказался второй слой ракушек.", 0, 3, 0),
            new ForestEvent("🐢 Черепаха сидела прямо на россыпи ракушек. Уступила.", 0, 2, 5),
            new ForestEvent("🌀 Вихрь подхватил листья — и пару ракушек унёс.", 0, -1, 0)
    );

    // Event fire chance per activity (0–100 %)
    private static final int CHOP_NEAR_EVENT_CHANCE = 20;
    private static final int CHOP_FAR_EVENT_CHANCE  = 25;
    private static final int GATHER_EVENT_CHANCE     = 30;

    // ── Narrative result text pools ───────────────────────────────────────────

    private static final List<String> NARRATIVES_CHOP_NEAR = List.of(
            "Ближний участок обработан. Берёзы срублены аккуратно.",
            "Работа знакомая, привычная. Руки сделали всё сами.",
            "Чистый участок. Дрова уложены ровными рядами.",
            "Близко к поселению — почти не устал."
    );

    private static final List<String> NARRATIVES_CHOP_FAR = List.of(
            "Дальняя чаща выдала добычу — пришлось пробираться через бурелом.",
            "Старые сосны поддались с третьего удара. Вернулся с ощущением победы.",
            "Там было темнее и тише. Но дерево там другое — тяжёлое, плотное.",
            "Дальний маршрут всегда длиннее, но и богаче."
    );

    private static final List<String> NARRATIVES_GATHER = List.of(
            "Под листьями нашлось много интересного. Корзина потяжелела.",
            "Тихая прогулка с острыми глазами — лучший сбор.",
            "Лесная тропинка редко разочаровывает тех, кто смотрит под ноги.",
            "Каждый клочок мха может скрывать сюрприз."
    );

    private final IslandRepository islandRepository;
    private final PlayerRepository playerRepository;
    private final Random random = new Random();

    // ── State checks ──────────────────────────────────────────────────────────

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
        long totalSeconds = Duration.between(LocalDateTime.now(), player.getForest().getFinishAt()).getSeconds();
        long minutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;
        if (minutes > 0) return minutes + " мин " + seconds + " сек";
        return seconds + " сек";
    }

    // ── Actions ───────────────────────────────────────────────────────────────

    public void startActivity(Player player, ForestActivity activity) {
        int level = player.getForest().getLevel();
        // Higher level = faster: -3% per level after 1, max -30%
        double speedMultiplier = Math.max(0.7, 1.0 - (level - 1) * 0.03);
        int minutes = (int) Math.round(activity.getBaseMinutes() * speedMultiplier);

        PlayerForest forest = player.getForest();
        forest.setActivity(activity);
        forest.setFinishAt(LocalDateTime.now().plusMinutes(Math.max(1, minutes)));
        forest.setNotified(false);
        playerRepository.save(player);
    }

    /**
     * Collect yield from a finished activity.
     * May include a random event (bonus or penalty + narrative text).
     */
    public ForestResult collectYield(Player player, Island island) {
        PlayerForest forest = player.getForest();
        ForestActivity activity = forest.getActivity();
        if (activity == null) activity = ForestActivity.CHOP_NEAR;

        int level = forest.getLevel();
        int woodBonus = level >= 3 ? 2 : (level >= 2 ? 1 : 0);

        int wood = 0;
        int shells = 0;

        if (activity.getWoodMax() > 0) {
            int min = activity.getWoodMin() + woodBonus;
            int max = activity.getWoodMax() + woodBonus;
            wood = min + random.nextInt(max - min + 1);
        }
        if (activity.getShellsMax() > 0) {
            shells = activity.getShellsMin()
                    + random.nextInt(activity.getShellsMax() - activity.getShellsMin() + 1);
        }

        int xpMultiplierPct = level >= 5 ? 130 : (level >= 3 ? 110 : 100);
        int xpEarned = (int) Math.round(activity.getXpReward() * xpMultiplierPct / 100.0);

        // ── Random event ──────────────────────────────────────────────────────
        ForestEvent event = rollEvent(activity);
        if (event != null) {
            wood   = Math.max(0, wood   + event.woodDelta());
            shells = Math.max(0, shells + event.shellsDelta());
            xpEarned += event.xpDelta();
        }

        // Apply to island (cap at storage)
        int storageLeft  = island.getStorageCapacity() - totalStored(island);
        int woodActual   = Math.min(wood,   storageLeft);
        int shellsActual = Math.min(shells, Math.max(0, storageLeft - woodActual));

        island.setWood(island.getWood() + woodActual);
        island.setShells(island.getShells() + shellsActual);
        islandRepository.save(island);

        // Level up
        int oldLevel = forest.getLevel();
        int totalXp  = forest.getXp() + xpEarned;
        int newLevel  = levelForXp(totalXp);

        forest.setXp(totalXp);
        forest.setLevel(newLevel);
        forest.setActivity(null);
        forest.setFinishAt(null);
        playerRepository.save(player);

        String narrative = pickNarrative(activity);
        return new ForestResult(activity, woodActual, shellsActual, xpEarned, totalXp,
                oldLevel, newLevel, narrative, event);
    }

    // ── Random event helpers ──────────────────────────────────────────────────

    private ForestEvent rollEvent(ForestActivity activity) {
        int chance = switch (activity) {
            case CHOP_NEAR -> CHOP_NEAR_EVENT_CHANCE;
            case CHOP_FAR  -> CHOP_FAR_EVENT_CHANCE;
            case GATHER    -> GATHER_EVENT_CHANCE;
        };
        if (random.nextInt(100) >= chance) return null;

        List<ForestEvent> pool = switch (activity) {
            case CHOP_NEAR -> EVENTS_CHOP_NEAR;
            case CHOP_FAR  -> EVENTS_CHOP_FAR;
            case GATHER    -> EVENTS_GATHER;
        };
        return pool.get(random.nextInt(pool.size()));
    }

    private static String pickNarrative(ForestActivity activity) {
        List<String> pool = switch (activity) {
            case CHOP_NEAR -> NARRATIVES_CHOP_NEAR;
            case CHOP_FAR  -> NARRATIVES_CHOP_FAR;
            case GATHER    -> NARRATIVES_GATHER;
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

    /** XP threshold that started the given level (exclusive lower bound). */
    public int xpForLevel(int level) {
        if (level <= 1) return 0;
        int idx = Math.min(level - 1, XP_THRESHOLDS.length - 1);
        return XP_THRESHOLDS[idx - 1];
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
            case 2 -> "+1 к минимальному сбору дерева на всех маршрутах.";
            case 3 -> "+2 дерева на всех маршрутах и +10% XP.";
            case 4 -> "🔓 Далёкий маршрут чуть быстрее — −3% времени.";
            case 5 -> "+30% XP за каждую вылазку.";
            case 6 -> "🌳 Особый бонус: редкие коряги в дальнем лесу.";
            default -> null;
        };
    }

    private static int totalStored(Island island) {
        return island.getWood() + island.getStone() + island.getFish()
                + island.getShells() + island.getCoral();
    }

    // ── Result + Event records ────────────────────────────────────────────────

    /**
     * A random in-trip event that modifies the yield and adds a narrative line.
     */
    public record ForestEvent(String text, int woodDelta, int shellsDelta, int xpDelta) {}

    /**
     * Full result of one completed forest activity.
     * {@code event} is null when no random event occurred.
     */
    public record ForestResult(
            ForestActivity activity,
            int woodGained,
            int shellsGained,
            int xpEarned,
            int totalXp,
            int oldLevel,
            int newLevel,
            String narrative,
            ForestEvent event
    ) {
        public boolean leveledUp() { return newLevel > oldLevel; }
        public boolean hasEvent()  { return event != null; }
    }
}
