package com.reefbot.enums;

public enum PlayerScreen {
    MAIN,
    MY_ISLAND,

    // Shore zone
    ZONE_SHORE,
    ZONE_SHORE_BEACH,
    ZONE_SHORE_TIDE,
    ZONE_SHORE_BUILDINGS,
    ZONE_SHORE_PIER,

    // Fishing
    FISHING_MENU,
    FISHING_ACTIVE,
    FISHING_RESULT,
    FISHING_BONUSES,
    FISHING_INVENTORY,

    // Forest zone
    ZONE_FOREST,
    ZONE_FOREST_HUNT_MENU,    // spot selection sub-screen
    ZONE_FOREST_HUNT_ACTIVE,  // waiting for hunt to finish
    ZONE_FOREST_HUNT_RESULT,  // collect hunt yield

    // Hills zone
    ZONE_HILLS,
    ZONE_HILLS_MINE,        // depth selection sub-screen

    // Settlement zone
    ZONE_SETTLEMENT,
    ZONE_SETTLEMENT_BONUS,  // daily bonus sub-screen

    // Other zones (stubs)
    ZONE_PLAINS,
    ZONE_PORT,

    // Misc
    NPC_ENCOUNTER
}
