package com.reefbot.enums;

/**
 * Типы зданий. Каждый тип знает свою конфигурацию по уровням.
 *
 * <p>Именованные уровни 1–3 хранятся в массиве {@code NAMED_LEVELS}.
 * Уровень 4+ считается по формулам бесконечной прогрессии.
 */
public enum BuildingType {

    /**
     * 🎣 Рыбацкий помост → Укреплённый помост → Рыбацкая пристань.
     * Пассивное производство рыбы, потолок 8 часов.
     */
    FISHING_PIER(
        new LevelConfig[]{
            // Level 1
            new LevelConfig("🎣 Рыбацкий помост",     30, 15,  0,  45, 5),
            // Level 2
            new LevelConfig("🎣 Укреплённый помост",  50, 20,  0, 120, 8),
            // Level 3
            new LevelConfig("🛶 Рыбацкая пристань",  100, 40, 15, 360, 12),
        },
        /* base for infinite levels: cost_fish=100, cost_shells=40, cost_wood=15, time_min=360, prod=12 */
        100, 40, 15, 360, 12
    );

    // ── Fields ──────────────────────────────────────────────────────────────

    private final LevelConfig[] namedLevels;

    /** Базовые значения уровня 3 — отправная точка для бесконечной прогрессии. */
    private final int baseFish, baseShells, baseWood, baseTimeMin, baseProd;

    /** Потолок хранилища в часах — одинаков для всех уровней. */
    public static final int CAP_HOURS = 8;

    // ── Constructor ──────────────────────────────────────────────────────────

    BuildingType(LevelConfig[] namedLevels,
                 int baseFish, int baseShells, int baseWood,
                 int baseTimeMin, int baseProd) {
        this.namedLevels  = namedLevels;
        this.baseFish     = baseFish;
        this.baseShells   = baseShells;
        this.baseWood     = baseWood;
        this.baseTimeMin  = baseTimeMin;
        this.baseProd     = baseProd;
    }

    // ── Public API ───────────────────────────────────────────────────────────

    /** Название здания на указанном уровне. */
    public String nameAt(int level) {
        if (level <= namedLevels.length) return namedLevels[level - 1].name();
        return namedLevels[namedLevels.length - 1].name() + "+" + (level - namedLevels.length);
    }

    /** Стоимость постройки/апгрейда ДО указанного уровня. */
    public int fishCostFor(int level) {
        if (level <= namedLevels.length) return namedLevels[level - 1].fishCost();
        return (int) Math.round(baseFish * Math.pow(1.6, level - namedLevels.length));
    }

    public int shellsCostFor(int level) {
        if (level <= namedLevels.length) return namedLevels[level - 1].shellsCost();
        return (int) Math.round(baseShells * Math.pow(1.6, level - namedLevels.length));
    }

    public int woodCostFor(int level) {
        if (level <= namedLevels.length) return namedLevels[level - 1].woodCost();
        return (int) Math.round(baseWood * Math.pow(1.6, level - namedLevels.length));
    }

    /** Время постройки/апгрейда ДО указанного уровня (минуты, max 24ч). */
    public int buildMinutesFor(int level) {
        if (level <= namedLevels.length) return namedLevels[level - 1].buildMinutes();
        int minutes = (int) Math.round(baseTimeMin * Math.pow(1.4, level - namedLevels.length));
        return Math.min(minutes, 24 * 60); // cap 24h
    }

    /** Производство ресурса в час на указанном уровне. */
    public int productionPerHourAt(int level) {
        if (level <= namedLevels.length) return namedLevels[level - 1].productionPerHour();
        return baseProd + 2 * (level - namedLevels.length);
    }

    /** Максимальное накопление (потолок) на указанном уровне. */
    public int capAt(int level) {
        return productionPerHourAt(level) * CAP_HOURS;
    }

    /** True если уровень требует древесины. */
    public boolean requiresWood(int level) {
        return woodCostFor(level) > 0;
    }

    // ── Inner record ──────────────────────────────────────────────────────────

    public record LevelConfig(
            String name,
            int fishCost,
            int shellsCost,
            int woodCost,
            int buildMinutes,
            int productionPerHour
    ) {}
}
