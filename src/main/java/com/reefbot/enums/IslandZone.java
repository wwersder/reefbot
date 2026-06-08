package com.reefbot.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum IslandZone {
    FOREST("🌲 Лесная зона", 0),
    COASTAL("🏖 Береговая зона", 0),
    SETTLEMENT("🏠 Жилая зона", 0),
    HILL("⛰ Холмовая зона", 10),
    PLAIN("🌾 Равнинная зона", 20),
    PORT("⚓ Портовая зона", 35);

    private final String displayName;
    private final int requiredDevelopmentPoints;

    public boolean isUnlocked(int islandDevelopmentPoints) {
        return islandDevelopmentPoints >= requiredDevelopmentPoints;
    }
}
