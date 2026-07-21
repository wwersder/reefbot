package com.reefbot.enums;

/**
 * Symbols for the 5×5 «Шторм vs Штиль» slot.
 * payouts[count] where count = 3, 4, or 5 (× bet per payline).
 * WILD and SCATTER have null payouts (no self-pay).
 */
public enum SlotWarSymbol {

    JELLYFISH("🪼", new double[]{0, 0, 0, 0.20, 0.40, 0.50}),
    SHELL    ("🐚", new double[]{0, 0, 0, 0.30, 0.60, 0.80}),
    CRAB     ("🦀", new double[]{0, 0, 0, 0.50, 0.80, 1.20}),
    FISH     ("🐠", new double[]{0, 0, 0, 0.80, 1.40, 2.00}),
    TURTLE   ("🐢", new double[]{0, 0, 0, 1.00, 2.00, 3.00}),
    OCTOPUS  ("🐙", new double[]{0, 0, 0, 2.00, 3.50, 5.00}),
    SHARK    ("🦈", new double[]{0, 0, 0, 3.00, 5.50, 8.00}),
    DOLPHIN  ("🐬", new double[]{0, 0, 0, 6.00,10.00,15.00}),
    WILD     ("🌊", null),
    SCATTER  ("🗼", null);

    private final String emoji;
    private final double[] payouts; // index = match count

    SlotWarSymbol(String emoji, double[] payouts) {
        this.emoji   = emoji;
        this.payouts = payouts;
    }

    public String  getEmoji()     { return emoji; }
    public boolean isPaying()     { return payouts != null; }
    public boolean isWild()       { return this == WILD; }
    public boolean isScatter()    { return this == SCATTER; }

    /** Returns payout multiplier for `count` of this symbol (3–5). 0 if not paying. */
    public double getPayout(int count) {
        if (payouts == null || count < 3 || count > 5) return 0;
        return payouts[count];
    }
}
