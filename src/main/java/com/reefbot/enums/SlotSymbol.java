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
 * <p>RTP BALANCE v8 (2026-06-22) — Per-payline SUM mechanic, simulation-verified:
 * Bonus buy 100× bet, E[FS] = 97.4×, buy RTP ≈ 97.4%, house edge ≈ 2.6%.
 *
 * KEY DATA from 50 000 FS sessions simulation:
 *   E[FS] = 97.4× bet (median 25.6×, P90 256×, P99 1025×).
 *   Mechanic: per-payline SUM of sticky wild mults (×1/×2/×3/×5).
 *   10 FS spins. Cap (MAX_WIN_MULTIPLIER=2500×) triggers in 0.058% of sessions.
 *
 * Scatter weight 0.019 (organic trigger ~1/591 spins, every ~89min at 400 spins/hr).
 * Scatter trigger bonus pays: 5×/15×/50× (see SlotService).
 * Bonus buy: 100× bet — E[FS]=97.4×, buy RTP=97.4% (SlotService.BONUS_BUY_MULTIPLIER).
 * Max win per FS session: 2500× bet (see SlotService.MAX_WIN_MULTIPLIER).
 */
public enum SlotSymbol {

    //                      weight   pay3   pay4    pay5
    FISH_CLOWN  (0.191,     0.5,   1.5,    4.0  ),   // weight 0.200→0.191 (−0.009 to scatter)
    FISH_PUFFER (0.18,      0.8,   2.5,    6.0  ),
    SHRIMP      (0.15,      1.5,   4.0,    9.0  ),
    FISH_BLUE   (0.14,      2.0,   5.0,    12.0 ),
    CRAB        (0.12,      3.0,   8.0,    20.0 ),
    OCTOPUS     (0.09,      5.0,   13.0,   36.0 ),
    SQUID       (0.06,      8.0,   22.0,   55.0 ),
    SHARK       (0.03,      16.0,  48.0,   150.0),
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
