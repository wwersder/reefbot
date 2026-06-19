package com.reefbot.dto.plinko;

import com.reefbot.enums.PlinkoRisk;

/**
 * Request body for POST /api/mini/plinko/play
 */
public record PlinkoPlayRequest(
        int bet,
        int rows,
        PlinkoRisk risk
) {}
