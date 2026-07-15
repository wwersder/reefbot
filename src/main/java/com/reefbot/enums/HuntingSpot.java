package com.reefbot.enums;

/**
 * Hunting spots in the Forest zone.
 * Mirrors the FishingSpot pattern: each spot has a duration, yield range, XP reward, and min level.
 */
public enum HuntingSpot {

    /** Forest edge — quick trip, modest yield. Always available. */
    EDGE(
            "🌿 Опушка",
            60,      // base minutes
            3, 8,    // meat min/max
            0, 0,    // fur min/max
            25,      // xp reward
            1        // min hunter level
    ),

    /** Deep forest — longer trip, better meat and fur. Requires level 3. */
    DEEP(
            "🌲 Чаща",
            180,
            8, 18,
            1, 3,
            60,
            3
    ),

    /** Wild territory — long haul, best yield. Requires level 6. */
    WILD(
            "🐾 Урочище",
            420,
            15, 30,
            3, 7,
            130,
            6
    );

    private final String displayName;
    private final int    baseMinutes;
    private final int    meatMin;
    private final int    meatMax;
    private final int    furMin;
    private final int    furMax;
    private final int    xpReward;
    private final int    minLevel;

    HuntingSpot(String displayName, int baseMinutes,
                int meatMin, int meatMax,
                int furMin,  int furMax,
                int xpReward, int minLevel) {
        this.displayName = displayName;
        this.baseMinutes = baseMinutes;
        this.meatMin     = meatMin;
        this.meatMax     = meatMax;
        this.furMin      = furMin;
        this.furMax      = furMax;
        this.xpReward    = xpReward;
        this.minLevel    = minLevel;
    }

    public String getDisplayName() { return displayName; }
    public int    getBaseMinutes() { return baseMinutes; }
    public int    getMeatMin()     { return meatMin; }
    public int    getMeatMax()     { return meatMax; }
    public int    getFurMin()      { return furMin; }
    public int    getFurMax()      { return furMax; }
    public int    getXpReward()    { return xpReward; }
    public int    getMinLevel()    { return minLevel; }
}
