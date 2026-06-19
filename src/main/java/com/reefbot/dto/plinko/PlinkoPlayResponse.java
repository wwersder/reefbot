package com.reefbot.dto.plinko;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Response for POST /api/mini/plinko/play
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PlinkoPlayResponse(
        // Success fields
        Integer slot,
        Double multiplier,
        Integer won,
        Integer profit,
        Integer newBalance,
        boolean[] path,

        // Error fields
        String error,
        String message
) {
    public static PlinkoPlayResponse success(int slot, double multiplier, int won,
                                             int profit, int newBalance, boolean[] path) {
        return new PlinkoPlayResponse(slot, multiplier, won, profit, newBalance, path, null, null);
    }

    public static PlinkoPlayResponse error(String code, String msg) {
        return new PlinkoPlayResponse(null, null, null, null, null, null, code, msg);
    }
}
