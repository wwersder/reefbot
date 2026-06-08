package com.reefbot.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum ResourceType {
    WOOD("🪵", "Древесина"),
    STONE("🪨", "Камень"),
    FISH("🐟", "Рыба"),
    SHELLS("🐚", "Ракушки"),
    CORAL("🪸", "Коралл");

    private final String emoji;
    private final String displayName;
}
