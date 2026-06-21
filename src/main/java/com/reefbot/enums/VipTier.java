package com.reefbot.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Permanent VIP tiers earned by cumulative lifetime wager across all games.
 * Once reached, a tier is never revoked.
 */
@Getter
@RequiredArgsConstructor
public enum VipTier {

    NONE  (          0L, 0.00, "Нет",    ""),
    CORAL (      5_000L, 0.03, "Коралл", "🪸"),
    PEARL (     25_000L, 0.06, "Жемчуг", "🦪"),
    REEF  (    100_000L, 0.10, "Риф",    "👑");

    /** Minimum cumulative wager (shells) required to enter this tier. */
    private final long wageredThreshold;

    /** Cashback rate applied to net loss at end of each period (0.0 = none). */
    private final double cashbackRate;

    /** Russian display name. */
    private final String displayName;

    /** Emoji icon. */
    private final String emoji;

    /**
     * Returns the highest tier the player qualifies for based on lifetime wager.
     * Never returns a tier lower than current (tiers are permanent).
     */
    public static VipTier forWager(long lifetimeWager) {
        VipTier best = NONE;
        for (VipTier t : values()) {
            if (lifetimeWager >= t.wageredThreshold) best = t;
        }
        return best;
    }

    /** Returns the next tier above this one, or null if already max. */
    public VipTier next() {
        VipTier[] vals = values();
        int idx = ordinal() + 1;
        return idx < vals.length ? vals[idx] : null;
    }

    /** Display string: emoji + name, e.g. "🪸 Коралл". Returns "-" for NONE. */
    public String label() {
        return this == NONE ? "—" : emoji + " " + displayName;
    }
}
