package com.reefbot.service.game;

import com.reefbot.entity.Island;
import com.reefbot.entity.Player;
import com.reefbot.entity.PlayerMine;
import com.reefbot.enums.MineDepth;
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
public class MineService {

    private static final int[] XP_THRESHOLDS =
            {0, 100, 250, 550, 1100, 2000, 3500, 5500, 8500, 12000, Integer.MAX_VALUE};

    private static final String[] LEVEL_NAMES = {
            "⛏ Копатель",        // 1
            "🪨 Горняк",          // 2
            "🔦 Шахтёр",          // 3
            "💎 Кристальщик",     // 4
            "🪝 Проходчик",       // 5
            "⚙️ Мастер шахты",   // 6
            "🌑 Глубинник",       // 7
            "🔮 Геолог",          // 8
            "🌋 Повелитель недр", // 9
            "✨ Легенда горы"     // 10
    };

    // ── Random event pools per depth ─────────────────────────────────────────

    private static final List<MineEvent> EVENTS_SHALLOW = List.of(
            new MineEvent("💎 Маленький кристалл блеснул в боковой жиле.", 0, 1, 0),
            new MineEvent("🌊 Грунтовая вода просочилась — пришлось работать медленнее.", -2, 0, 0),
            new MineEvent("🪨 Залежь плотного кварца — пришлось долбить дольше.", -1, 0, 5)
    );

    private static final List<MineEvent> EVENTS_MEDIUM = List.of(
            new MineEvent("⚡ Нашёл руду с вкраплениями кристаллов — повезло!", 0, 2, 0),
            new MineEvent("💥 Небольшой обвал. Часть добычи погребена.", -4, 0, 0),
            new MineEvent("🔦 Старая фляга с запиской — прошлый шахтёр указал жилу!", 2, 1, 8),
            new MineEvent("🌡 Горячий пар из трещины — выбрался быстро.", -2, 0, 5)
    );

    private static final List<MineEvent> EVENTS_DEEP = List.of(
            new MineEvent("🌟 Жила кораллового камня — редкость в этих глубинах!", 0, 3, 0),
            new MineEvent("🔥 Горячий источник. Обожгло руки, но нашлись кристаллы.", 0, 2, 15),
            new MineEvent("🌊 Подземное озеро — пришлось срочно подниматься. Добыча неполная.", -6, 0, 8),
            new MineEvent("💎 Жеода! Внутри идеальный кристалл коралла.", 0, 3, 10),
            new MineEvent("🌑 Темнота поглотила фонарь. Вышел на ощупь. Ничего не потерял.", 0, 0, 12)
    );

    private static final int SHALLOW_EVENT_CHANCE = 15;
    private static final int MEDIUM_EVENT_CHANCE  = 22;
    private static final int DEEP_EVENT_CHANCE    = 28;

    // ── Narrative result text pools ───────────────────────────────────────────

    private static final List<String> NARRATIVES_SHALLOW = List.of(
            "Поверхностный слой — знакомая работа. Быстро и надёжно.",
            "Верхний горизонт сдался без боя. Камень здесь мягкий.",
            "Поверхность всегда щедра для тех, кто умеет смотреть."
    );

    private static final List<String> NARRATIVES_MEDIUM = List.of(
            "На среднем горизонте порода плотнее. Зато и камень качественнее.",
            "Средняя шахта — это про терпение. Всё получилось.",
            "Долбил долго, но результат того стоит."
    );

    private static final List<String> NARRATIVES_DEEP = List.of(
            "Глубина — другой мир. Тихий, тёмный, богатый.",
            "Там внизу время идёт иначе. Вернулся с полными карманами.",
            "Глубокая шахта не прощает спешки — но щедро платит за усердие.",
            "Эха кирки ещё звенит в ушах. Добыча достойная."
    );

    private final IslandRepository islandRepository;
    private final PlayerRepository playerRepository;
    private final Random random = new Random();

    // ── State checks ──────────────────────────────────────────────────────────

    public boolean isActive(Player player) {
        LocalDateTime finishAt = player.getMine().getFinishAt();
        return finishAt != null && LocalDateTime.now().isBefore(finishAt);
    }

    public boolean isReady(Player player) {
        LocalDateTime finishAt = player.getMine().getFinishAt();
        return finishAt != null && !LocalDateTime.now().isBefore(finishAt);
    }

    public boolean isIdle(Player player) {
        return player.getMine().getFinishAt() == null;
    }

    public String timeRemainingText(Player player) {
        if (!isActive(player)) return "0 сек";
        long totalSeconds = Duration.between(LocalDateTime.now(), player.getMine().getFinishAt()).getSeconds();
        long minutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;
        if (minutes > 0) return minutes + " мин " + seconds + " сек";
        return seconds + " сек";
    }

    // ── Actions ───────────────────────────────────────────────────────────────

    public void startMining(Player player, MineDepth depth) {
        int level = player.getMine().getLevel();
        // Higher level = faster: -4% per level after 1, max -36%
        double speedMultiplier = Math.max(0.64, 1.0 - (level - 1) * 0.04);
        int minutes = (int) Math.round(depth.getBaseMinutes() * speedMultiplier);

        PlayerMine mine = player.getMine();
        mine.setDepth(depth.getDepth());
        mine.setFinishAt(LocalDateTime.now().plusMinutes(Math.max(1, minutes)));
        mine.setNotified(false);
        playerRepository.save(player);
    }

    public MineResult collectYield(Player player, Island island) {
        PlayerMine mine = player.getMine();
        MineDepth depth = mine.getDepth() != null
                ? MineDepth.fromDepthValue(mine.getDepth())
                : MineDepth.SHALLOW;

        int level = mine.getLevel();
        int stoneBonus = level >= 3 ? 3 : (level >= 2 ? 1 : 0);

        int stoneMin = depth.getStoneMin() + stoneBonus;
        int stoneMax = depth.getStoneMax() + stoneBonus;
        int stone = stoneMin + random.nextInt(stoneMax - stoneMin + 1);

        int coral = 0;
        if (depth.getCoralMax() > 0) {
            int min = depth.getCoralMin();
            int max = depth.getCoralMax() + (level >= 5 ? 2 : 0);
            if (random.nextInt(100) < coralChancePct(depth, level)) {
                coral = min + random.nextInt(Math.max(1, max - min + 1));
            }
        }

        int xpMultiplierPct = level >= 5 ? 130 : (level >= 3 ? 110 : 100);
        int xpEarned = (int) Math.round(depth.getXpReward() * xpMultiplierPct / 100.0);

        // ── Random event ──────────────────────────────────────────────────────
        MineEvent event = rollEvent(depth);
        if (event != null) {
            stone    = Math.max(0, stone + event.stoneDelta());
            coral    = Math.max(0, coral + event.coralDelta());
            xpEarned += event.xpDelta();
        }

        // Apply to island (cap at storage)
        int storageLeft  = island.getStorageCapacity() - totalStored(island);
        int stoneActual  = Math.min(stone, storageLeft);
        int coralActual  = Math.min(coral, Math.max(0, storageLeft - stoneActual));

        island.setStone(island.getStone() + stoneActual);
        island.setCoral(island.getCoral() + coralActual);
        islandRepository.save(island);

        int oldLevel = mine.getLevel();
        int totalXp  = mine.getXp() + xpEarned;
        int newLevel  = levelForXp(totalXp);

        mine.setXp(totalXp);
        mine.setLevel(newLevel);
        mine.setDepth(null);
        mine.setFinishAt(null);
        playerRepository.save(player);

        String narrative = pickNarrative(depth);
        return new MineResult(depth, stoneActual, coralActual, xpEarned, totalXp,
                oldLevel, newLevel, narrative, event);
    }

    // ── Random event helpers ──────────────────────────────────────────────────

    private MineEvent rollEvent(MineDepth depth) {
        int chance = switch (depth) {
            case SHALLOW -> SHALLOW_EVENT_CHANCE;
            case MEDIUM  -> MEDIUM_EVENT_CHANCE;
            case DEEP    -> DEEP_EVENT_CHANCE;
        };
        if (random.nextInt(100) >= chance) return null;

        List<MineEvent> pool = switch (depth) {
            case SHALLOW -> EVENTS_SHALLOW;
            case MEDIUM  -> EVENTS_MEDIUM;
            case DEEP    -> EVENTS_DEEP;
        };
        return pool.get(random.nextInt(pool.size()));
    }

    private static String pickNarrative(MineDepth depth) {
        List<String> pool = switch (depth) {
            case SHALLOW -> NARRATIVES_SHALLOW;
            case MEDIUM  -> NARRATIVES_MEDIUM;
            case DEEP    -> NARRATIVES_DEEP;
        };
        return pool.get((int) (Math.random() * pool.size()));
    }

    private int coralChancePct(MineDepth depth, int level) {
        return switch (depth) {
            case SHALLOW -> 0;
            case MEDIUM  -> 40 + (level - 1) * 5;
            case DEEP    -> 70 + (level - 1) * 5;
        };
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
            case 2 -> "+1 к добыче камня на всех уровнях шахты.";
            case 3 -> "+3 камня на всех уровнях и +10% XP.";
            case 4 -> "🔓 Средний уровень шахты стал быстрее на −4%.";
            case 5 -> "+2 к максимуму коралла на средних и глубоких уровнях.";
            default -> null;
        };
    }

    private static int totalStored(Island island) {
        return island.getWood() + island.getStone() + island.getFish()
                + island.getShells() + island.getCoral();
    }

    // ── Result + Event records ────────────────────────────────────────────────

    public record MineEvent(String text, int stoneDelta, int coralDelta, int xpDelta) {}

    public record MineResult(
            MineDepth depth,
            int stoneGained,
            int coralGained,
            int xpEarned,
            int totalXp,
            int oldLevel,
            int newLevel,
            String narrative,
            MineEvent event
    ) {
        public boolean leveledUp() { return newLevel > oldLevel; }
        public boolean hasEvent()  { return event != null; }
    }
}
