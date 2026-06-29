package com.reefbot.util;

/**
 * Renders a compact visual XP progress bar for Telegram messages.
 *
 * <pre>
 *   XpBar.render(320, 450, 10)  →  "████████░░ 320/450 ⭐"
 *   XpBar.render(10000, 0, 10)  →  "★ МАКС. УРОВЕНЬ"
 * </pre>
 */
public final class XpBar {

    private static final String FILLED = "█";
    private static final String EMPTY  = "░";
    private static final int    WIDTH  = 10;

    private XpBar() {}

    /**
     * Renders an XP bar at fixed width of {@value #WIDTH}.
     *
     * @param current current XP
     * @param max     XP needed for next level (0 = max level reached)
     */
    public static String render(int current, int max) {
        if (max <= 0) return "★ МАКС. УРОВЕНЬ";
        double pct    = Math.min(1.0, (double) current / max);
        int    filled = (int) Math.round(pct * WIDTH);
        return FILLED.repeat(filled) + EMPTY.repeat(WIDTH - filled)
                + " " + Fmt.n(current) + "/" + Fmt.n(max) + " ⭐";
    }

    /**
     * Renders an XP bar with explicit fill count for use inside compact layouts.
     * Useful when the caller has already computed the level thresholds.
     *
     * @param current     XP in current level (i.e. current − threshold[level-1])
     * @param levelNeeded XP needed for the level (i.e. threshold[level] − threshold[level-1])
     * @param totalXp     total cumulative XP (shown after the bar)
     */
    public static String renderDelta(int current, int levelNeeded, int totalXp) {
        if (levelNeeded <= 0) return "★ МАКС. УРОВЕНЬ";
        double pct    = Math.min(1.0, (double) current / levelNeeded);
        int    filled = (int) Math.round(pct * WIDTH);
        return FILLED.repeat(filled) + EMPTY.repeat(WIDTH - filled)
                + " +" + Fmt.n(current) + "/" + Fmt.n(levelNeeded) + " ⭐";
    }
}
