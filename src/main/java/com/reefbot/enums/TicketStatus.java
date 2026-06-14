package com.reefbot.enums;

public enum TicketStatus {
    OPEN,
    IN_PROGRESS,
    RESOLVED,
    CLOSED_BY_PLAYER;

    public String emoji() {
        return switch (this) {
            case OPEN             -> "🟡";
            case IN_PROGRESS      -> "🔵";
            case RESOLVED         -> "✅";
            case CLOSED_BY_PLAYER -> "🔴";
        };
    }

    public String displayName() {
        return switch (this) {
            case OPEN             -> "Открыт";
            case IN_PROGRESS      -> "В работе";
            case RESOLVED         -> "Решён";
            case CLOSED_BY_PLAYER -> "Закрыт игроком";
        };
    }

    public boolean isActive() {
        return this == OPEN || this == IN_PROGRESS;
    }
}
