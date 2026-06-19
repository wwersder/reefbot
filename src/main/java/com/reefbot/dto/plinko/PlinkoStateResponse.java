package com.reefbot.dto.plinko;

/**
 * Response for GET /api/mini/plinko/state
 */
public record PlinkoStateResponse(
        boolean onboardingRequired,
        String message,

        // Present only when onboardingRequired=false
        Integer balance,
        Integer dailyLost,
        Integer dailyLimitRemaining,
        Integer fisherLevel,
        boolean rows8Unlocked,
        boolean rows12Unlocked
) {
    /** Factory for onboarding-blocked response. */
    public static PlinkoStateResponse blocked() {
        return new PlinkoStateResponse(
                true,
                "Сначала заверши регистрацию в боте — напиши /start",
                null, null, null, null, false, false
        );
    }

    /** Factory for normal state response. */
    public static PlinkoStateResponse ok(int balance, int dailyLost, int fisherLevel) {
        int remaining = Math.max(0, 2000 - dailyLost);
        return new PlinkoStateResponse(
                false, null,
                balance, dailyLost, remaining,
                fisherLevel,
                true,
                fisherLevel >= 5
        );
    }
}
