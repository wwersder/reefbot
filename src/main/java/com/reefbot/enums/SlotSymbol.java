package com.reefbot.enums;

/**
 * Symbols used in The Reef House 5×3 video slot.
 *
 * <p>Each symbol has a spawn weight (all weights sum to 1.0) and payout
 * multipliers for 3-of-a-kind, 4-of-a-kind, and 5-of-a-kind on a payline.
 * Payouts are applied to the total spin bet.
 *
 * <p>WILD substitutes for any paying symbol (not SCATTER).
 * SCATTER pays anywhere on grid and triggers free spins on 3+.
 *
 * <p>Approximate base-game RTP: ~97% on 9 paylines.
 */
public enum SlotSymbol {

    //                      weight  pay3   pay4    pay5
    //                      weight  pay3   pay4    pay5
    // pay3 reduced ~5-8% from original. pay4/pay5 unchanged.
    // Target: base-game RTP ~95%, total with free-spins ~97-98%.
    // House edge ~2-3% — economy drains slowly, players still win often.
    FISH_CLOWN  (0.20,      0.95,  2.5,   6.0  ),
    FISH_PUFFER (0.18,      1.4,   4.0,   10.0 ),
    SHRIMP      (0.15,      2.3,   6.0,   15.0 ),
    FISH_BLUE   (0.14,      2.8,   8.0,   20.0 ),
    CRAB        (0.12,      4.7,   12.0,  35.0 ),
    OCTOPUS     (0.09,      7.5,   20.0,  60.0 ),
    SQUID       (0.06,      14.0,  40.0,  100.0),
    SHARK       (0.03,      27.0,  80.0,  250.0),
    WILD        (0.02,      0,     0,     0    ),
    SCATTER     (0.01,      0,     0,     0    );

    private final double weight;
    private final double pay3;
    private final double pay4;
    private final double pay5;

    SlotSymbol(double weight, double pay3, double pay4, double pay5) {
        this.weight = weight;
        this.pay3   = pay3;
        this.pay4   = pay4;
        this.pay5   = pay5;
    }

    public double getWeight() { return weight; }

    /** Payout multiplier for `count` matching symbols (3–5). 0 if non-paying. */
    public double getPayout(int count) {
        return switch (count) {
            case 3 -> pay3;
            case 4 -> pay4;
            case 5 -> pay5;
            default -> 0;
        };
    }

    public boolean isWild()    { return this == WILD; }
    public boolean isScatter() { return this == SCATTER; }
    public boolean isPaying()  { return !isWild() && !isScatter(); }
}
