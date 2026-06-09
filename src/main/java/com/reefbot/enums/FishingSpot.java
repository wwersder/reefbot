package com.reefbot.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum FishingSpot {

    SHORE(
            "🏖 У берега",
            1,
            1, 3,
            5,
            null, 0,
            1
    ),
    REEF(
            "🪨 У рифа",
            10,
            4, 8,
            15,
            ResourceType.SHELLS, 15,
            2
    ),
    OPEN_SEA(
            "🌊 В открытом море",
            30,
            10, 20,
            35,
            ResourceType.CORAL, 25,
            3
    );

    private final String displayName;
    private final int durationMinutes;
    private final int minFish;
    private final int maxFish;
    private final int xpReward;
    private final ResourceType bonusResource; // null = no bonus
    private final int bonusChance;            // percent (0–100)
    private final int minLevel;
}
