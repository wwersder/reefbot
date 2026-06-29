package com.reefbot.enums;

/**
 * Mine depth levels for the Hills zone.
 * Deeper = longer duration + rarer resources.
 */
public enum MineDepth {

    SHALLOW(
            1,
            "🔦 Поверхностный",
            15,     // base minutes
            5, 12,  // stone min/max
            0, 0    // coral bonus min/max
    ),

    MEDIUM(
            2,
            "⛏ Средний",
            35,
            10, 22,
            1, 3
    ),

    DEEP(
            3,
            "💎 Глубокий",
            70,
            18, 35,
            2, 6
    );

    private final int depth;
    private final String displayName;
    private final int baseMinutes;
    private final int stoneMin;
    private final int stoneMax;
    private final int coralMin;
    private final int coralMax;

    MineDepth(int depth, String displayName, int baseMinutes,
              int stoneMin, int stoneMax, int coralMin, int coralMax) {
        this.depth = depth;
        this.displayName = displayName;
        this.baseMinutes = baseMinutes;
        this.stoneMin = stoneMin;
        this.stoneMax = stoneMax;
        this.coralMin = coralMin;
        this.coralMax = coralMax;
    }

    public int    getDepth()       { return depth; }
    public String getDisplayName() { return displayName; }
    public int    getBaseMinutes() { return baseMinutes; }
    public int    getStoneMin()    { return stoneMin; }
    public int    getStoneMax()    { return stoneMax; }
    public int    getCoralMin()    { return coralMin; }
    public int    getCoralMax()    { return coralMax; }

    public int getXpReward() {
        return switch (this) {
            case SHALLOW -> 15;
            case MEDIUM  -> 35;
            case DEEP    -> 70;
        };
    }

    public static MineDepth fromDepthValue(int val) {
        for (MineDepth d : values()) {
            if (d.depth == val) return d;
        }
        return SHALLOW;
    }
}
