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
 * <p>RTP BALANCE v2 (2026-06-22):
 * Base-game ~87.8%, total with free-spins ~95.5%, house edge ~4.5%.
 * Scatter weight raised 0.01→0.02 (organic trigger 1/538 spins).
 * Weight transferred from FISH_CLOWN (0.20→0.19).
 * Scatter trigger bonus pays: 5×/15×/50× (see SlotService).
 * Bonus buy: 40× bet (SlotService.BONUS_BUY_MULTIPLIER), RTP ~91%.
 */
public enum SlotSymbol {

    //                      weight  pay3   pay4   pay5
    FISH_CLOWN  (0.19,      0.9,   2.4,   5.5  ),
    FISH_PUFFER (0.18,      1.3,   3.8,   9.5  ),
    SHRIMP      (0.15,      2.2,   5.7,   14.0 ),
    FISH_BLUE   (0.14,      2.6,   7.5,   19.0 ),
    CRAB        (0.12,      4.5,   11.5,  33.0 ),
    OCTOPUS     (0.09,      7.0,   19.0,  57.0 ),
    SQUID       (0.06,      13.0,  38.0,  95.0 ),
    SHARK       (0.03,      25.0,  76.0,  235.0),
    WILD        (0.02,      0,     0,     0    ),
    SCATTER     (0.02,      0,     0,     0    );

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
