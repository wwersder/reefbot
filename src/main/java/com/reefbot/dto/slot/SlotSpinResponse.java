package com.reefbot.dto.slot;

import com.reefbot.entity.Player;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

public record SlotSpinResponse(
        String         error,

        // Grid: [reel 0..4][row 0..2] symbol names
        String[][]     grid,

        List<WinLine>  wins,
        int            totalWin,
        int            scatterCount,
        int            scatterAmount,

        boolean        isFreeSpinTrigger,
        boolean        wasFreeSpins,
        int            freeSpinsRemaining,
        int            multiplier,

        int            newBalance,

        // VIP
        String         vipTier,
        long           vipLifetimeWager,
        int            vipPeriodNetLoss,
        int            vipEstimatedCashback,
        Long           vipNextTierThreshold,
        String         vipNextCashbackDate
) {
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd.MM");

    public static SlotSpinResponse error(String code) {
        return new SlotSpinResponse(
                code, null, null, 0, 0, 0,
                false, false, 0, 1, 0,
                null, 0L, 0, 0, null, null);
    }

    public static SlotSpinResponse ok(
            String[][] grid,
            List<WinLine> wins,
            int totalWin,
            int scatterCount,
            int scatterAmount,
            boolean isFreeSpinTrigger,
            boolean wasFreeSpins,
            Player player
    ) {
        var tier     = player.getVipTier();
        var next     = tier.next();
        int loss     = Math.max(0, player.getVipPeriodNetLoss());
        int cashback = (int)(loss * tier.getCashbackRate());

        return new SlotSpinResponse(
                null,
                grid,
                wins,
                totalWin,
                scatterCount,
                scatterAmount,
                isFreeSpinTrigger,
                wasFreeSpins,
                player.getSlotFreeSpinsRemaining(),
                player.getSlotMultiplier(),
                player.getIsland() != null ? player.getIsland().getShells() : 0,
                tier.name(),
                player.getVipLifetimeWager(),
                loss,
                cashback,
                next != null ? next.getWageredThreshold() : null,
                nextCashbackDate()
        );
    }

    private static String nextCashbackDate() {
        LocalDate today = LocalDate.now();
        DayOfWeek dow   = today.getDayOfWeek();
        int daysToMon   = (8 - dow.getValue()) % 7;
        int daysToThu   = (4 - dow.getValue() + 7) % 7;
        int days        = Math.min(daysToMon == 0 ? 7 : daysToMon,
                                   daysToThu == 0 ? 7 : daysToThu);
        LocalDate next  = today.plusDays(days);
        String dayName  = next.getDayOfWeek() == DayOfWeek.MONDAY ? "пн" : "чт";
        return dayName + " " + next.format(DATE_FMT);
    }
}
