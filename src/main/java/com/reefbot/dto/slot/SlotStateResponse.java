package com.reefbot.dto.slot;

import com.reefbot.entity.Player;
import com.reefbot.enums.VipTier;
import com.reefbot.service.plinko.VipService;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

public record SlotStateResponse(
        boolean onboardingRequired,
        String  message,

        int     balance,
        int     freeSpinsRemaining,
        int     multiplier,
        int     fsPendingWin,
        boolean rows12Unlocked,

        // VIP
        String  vipTier,
        String  vipTierLabel,
        String  vipTierEmoji,
        int     vipCashbackPercent,
        long    vipLifetimeWager,
        long    vipPeriodNetLoss,
        long    vipEstimatedCashback,
        Long    vipNextTierThreshold,
        String  vipNextCashbackDate
) {
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd.MM");

    public static SlotStateResponse onboarding(String msg) {
        return new SlotStateResponse(true, msg,
                0, 0, 1, 0, false,
                "NONE", "Нет", "", 0, 0L, 0L, 0L, null, null);
    }

    public static SlotStateResponse ok(Player p) {
        var tier = p.getVipTier();
        var next = tier.next();
        long loss     = Math.max(0L, p.getVipPeriodNetLoss());
        long cashback = (long)(loss * tier.getCashbackRate());
        int balance   = p.getIsland() != null ? p.getIsland().getShells() : 0;
        int fisherLvl = p.getFishing() != null ? p.getFishing().getFishingLevel() : 1;
        var ss        = p.getSlotState();

        return new SlotStateResponse(
                false, null,
                balance,
                ss != null ? ss.getFreeSpinsRemaining() : 0,
                ss != null ? ss.getMultiplier()         : 1,
                ss != null ? ss.getFsPendingWin()       : 0,
                fisherLvl >= 5,

                tier.name(),
                tier.getDisplayName(),
                tier.getEmoji(),
                (int)(tier.getCashbackRate() * 100),
                p.getVipLifetimeWager(),
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
