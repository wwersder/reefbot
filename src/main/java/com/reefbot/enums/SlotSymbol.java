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
 * <p>RTP BALANCE v7 (2026-06-22) — Per-payline SUM mechanic, simulation-verified:
 * Base-game ~75.4%, total ~96–98%, house edge ~2–4%.
 *
 * KEY DATA from 1 000 000 FS simulation:
 *   E[FS] = 128.71× bet (median 32.8×, P75 123.4×, P90 334×, P99 1377×).
 *   Mechanic: per-payline SUM of sticky wild mults (×1/×2/×3/×5).
 *   10 FS spins. Cap (MAX_WIN_MULTIPLIER=2500×) triggers in 0.21% of sessions.
 *
 * Scatter weight 0.019 (organic trigger ~1/591 spins, every ~89min at 400 spins/hr).
 * Weight transferred from FISH_CLOWN (0.200→0.191).
 * Scatter trigger bonus pays: 5×/15×/50× (see SlotService).
 * Bonus buy: 134× bet — E[FS]=128.71×, buy RTP=96.1% (SlotService.BONUS_BUY_MULTIPLIER).
 * Max win per FS session: 2500× bet (see SlotService.MAX_WIN_MULTIPLIER).
 */
public enum SlotSymbol {

    //                      weight   pay3   pay4    pay5
    FISH_CLOWN  (0.191,     0.80,  2.0,    5.0  ),   // weight 0.200→0.191 (−0.009 to scatter)
    FISH_PUFFER (0.18,      1.1,   3.2,    8.0  ),
    SHRIMP      (0.15,      1.9,   5.0,    12.0 ),
    FISH_BLUE   (0.14,      2.3,   6.5,    16.0 ),
    CRAB        (0.12,      4.0,   10.0,   28.0 ),
    OCTOPUS     (0.09,      6.0,   16.0,   48.0 ),
    SQUID       (0.06,      11.5,  33.0,   80.0 ),
    SHARK       (0.03,      22.0,  65.0,   200.0),
    WILD        (0.02,      0,     0,      0    ),
    SCATTER     (0.019,     0,     0,      0    );   // trigger ~1/591, every ~89min at 400/hr
    // Sum: 0.191+0.18+0.15+0.14+0.12+0.09+0.06+0.03+0.02+0.019 = 1.000 ✓

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
