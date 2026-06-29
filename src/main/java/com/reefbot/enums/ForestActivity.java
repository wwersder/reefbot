package com.reefbot.enums;

/**
 * Types of forest activities a player can do.
 * Duration and yield scale with lumberjack level.
 */
public enum ForestActivity {

    /** Chop trees nearby — shorter trip, less wood. */
    CHOP_NEAR(
            "🌳 Поближе",
            20,   // base minutes
            5, 12, // wood min/max
            0, 0  // shells bonus: min/max
    ),

    /** Chop trees deep in the forest — longer trip, more wood. */
    CHOP_FAR(
            "🌲 Подальше",
            45,
            12, 28,
            0, 2  // chance to find rare shells in far forest
    ),

    /** Gather mushrooms near the forest edge — quick, gives shells. */
    GATHER(
            "🍄 Собирать",
            15,
            0, 0,
            3, 8
    );

    private final String displayName;
    private final int baseMinutes;
    private final int woodMin;
    private final int woodMax;
    private final int shellsMin;
    private final int shellsMax;

    ForestActivity(String displayName, int baseMinutes,
                   int woodMin, int woodMax,
                   int shellsMin, int shellsMax) {
        this.displayName = displayName;
        this.baseMinutes = baseMinutes;
        this.woodMin = woodMin;
        this.woodMax = woodMax;
        this.shellsMin = shellsMin;
        this.shellsMax = shellsMax;
    }

    public String getDisplayName()   { return displayName; }
    public int    getBaseMinutes()   { return baseMinutes; }
    public int    getWoodMin()       { return woodMin; }
    public int    getWoodMax()       { return woodMax; }
    public int    getShellsMin()     { return shellsMin; }
    public int    getShellsMax()     { return shellsMax; }

    /** XP rewarded on completion. */
    public int getXpReward() {
        return switch (this) {
            case CHOP_NEAR -> 20;
            case CHOP_FAR  -> 50;
            case GATHER    -> 12;
        };
    }
}
