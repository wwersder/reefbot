package com.reefbot.service.game;

import com.reefbot.enums.FishingSpot;
import com.reefbot.enums.ResourceType;

/**
 * Result of a completed fishing session.
 *
 * @param spot         where the player fished
 * @param fishCaught   number of fish caught
 * @param bonusType    bonus resource type (null if none)
 * @param bonusAmount  number of bonus resources (0 if none)
 * @param xpEarned     XP earned this session
 * @param totalXp      total XP after this session
 * @param newLevel     fishing level after this session
 * @param wasFirstCatch true if this is the player's first completed fishing
 */
public record FishingResult(
        FishingSpot spot,
        int fishCaught,
        ResourceType bonusType,
        int bonusAmount,
        int xpEarned,
        int totalXp,
        int newLevel,
        boolean wasFirstCatch
) {}
