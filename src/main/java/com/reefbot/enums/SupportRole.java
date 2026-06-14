package com.reefbot.enums;

public enum SupportRole {
    SUPPORT,
    SUPER_ADMIN;

    public boolean canOverride(SupportRole other) {
        // SUPER_ADMIN can override anyone; SUPPORT cannot override SUPER_ADMIN
        return this == SUPER_ADMIN || other == SUPPORT;
    }
}
