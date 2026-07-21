package com.reefbot.dto.slotwar;

/** A winning payline result (before the global wild multiplier is applied). */
public record WinLineWar(int lineIndex, String symbol, int count, int amount, int[] rows) {}
