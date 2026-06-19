package com.reefbot.dto.plinko;

import java.util.List;

/**
 * Response for GET /api/mini/plinko/leaderboard
 */
public record PlinkoLeaderboardResponse(
        List<LeaderboardEntry> topWin,
        List<LeaderboardEntry> topMultiplier
) {
    public record LeaderboardEntry(
            String username,
            int profit,
            int won,
            double multiplier,
            int bet,
            String date
    ) {}
}
