package com.reefbot.enums;

public enum SlotWarMode {
    CALM("☀️ Штиль"),
    STORM("⚡ Шторм");

    private final String displayName;

    SlotWarMode(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() { return displayName; }
}
