package com.reefbot.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Inventory consumable items.
 * item_type = "CONSUMABLE", item_key = name().
 */
@Getter
@RequiredArgsConstructor
public enum ConsumableItem {

    SPEED_SCROLL("📜 Свиток ускорения", "Следующий заброс −50% времени"),
    TIDE_VIAL   ("🫙 Склянка прилива",  "Немедленно завершает текущую рыбалку"),
    BAIT        ("🪱 Морская наживка",  "Следующий улов +50% рыбы"),
    FISHING_HOOK("🪝 Старый крюк",      "+40 XP к следующему улову");

    private final String displayName;
    private final String description;

    public static final String ITEM_TYPE = "CONSUMABLE";
}
