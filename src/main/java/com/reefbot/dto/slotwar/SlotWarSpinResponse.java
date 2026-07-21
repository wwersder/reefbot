package com.reefbot.dto.slotwar;

import com.reefbot.entity.PlayerSlotWarState;

import java.util.List;

public record SlotWarSpinResponse(
        String error,

        // Grid: grid[col][row], symbol names (5×5)
        String[][]          grid,

        // Wilds that expanded this spin, each with their rolled multiplier
        List<ExpandedWild>  expandedWilds,

        // Winning paylines BEFORE the global wild multiplier
        List<WinLineWar>    winLines,
        int                 paylineWin,

        // Global multiplier = sum of all expanded (including sticky) wild mults
        int                 totalMult,

        // Final win = paylineWin × totalMult
        int                 finalWin,

        int                 scatterCount,
        boolean             bonusTriggered,
        boolean             wasFreeSpins,
        int                 freeSpinsRemaining,

        // Current sticky columns for FS (includes this spin's new expansions)
        String              stickyColumnsJson,
        int                 fsPendingWin,

        int                 newBalance,
        String              mode
) {
    public static SlotWarSpinResponse error(String code) {
        return new SlotWarSpinResponse(
                code, null, List.of(), List.of(),
                0, 1, 0, 0, false, false, 0, null, 0, 0, null);
    }

    public static SlotWarSpinResponse ok(
            String[][] grid,
            List<ExpandedWild> expandedWilds,
            List<WinLineWar> winLines,
            int paylineWin,
            int totalMult,
            int finalWin,
            int scatterCount,
            boolean bonusTriggered,
            boolean wasFreeSpins,
            PlayerSlotWarState sw,
            int newBalance,
            String mode
    ) {
        return new SlotWarSpinResponse(
                null, grid, expandedWilds, winLines,
                paylineWin, totalMult, finalWin, scatterCount,
                bonusTriggered, wasFreeSpins,
                sw != null ? sw.getFreeSpinsRemaining() : 0,
                sw != null ? sw.getStickyColumnsJson() : null,
                sw != null ? sw.getFsPendingWin() : 0,
                newBalance,
                mode
        );
    }
}
