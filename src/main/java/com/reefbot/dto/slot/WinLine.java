package com.reefbot.dto.slot;

/**
 * A single winning payline result returned by the slot engine.
 *
 * @param lineIndex  index of the payline (0–8)
 * @param symbol     winning symbol name (SlotSymbol.name())
 * @param count      consecutive matching symbols (3, 4, or 5)
 * @param amount     shells won on this line (bet × payout_multiplier)
 * @param rows       row index per reel on this payline [5 elements]
 */
public record WinLine(
        int    lineIndex,
        String symbol,
        int    count,
        int    amount,
        int[]  rows
) {}
