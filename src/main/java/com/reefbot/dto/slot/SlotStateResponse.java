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
        boolean rows12Unlocked,

        // VIP
        String  vipTier,
        String  vipTierLabel,
        String  vipTierEmoji,
        int     vipCashbackPercent,
        long    vipLifetimeWager,
        int     vipPeriodNetLoss,
        int     vipEstimatedCashback,
        Long    vipNextTierThreshold,
        String  vipNextCashbackDate
) {
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd.MM");

    public static SlotStateResponse onboarding(String msg) {
        return new SlotStateResponse(true, msg,
                0, 0, 1, false,
                "NONE", "Нет", "", 0, 0L, 0, 0, null, null);
    }

    public static SlotStateResponse ok(Player p) {
        var tier = p.getVipTier();
        var next = tier.next();
        int loss      = Math.max(0, p.getVipPeriodNetLoss());
        int cashback  = (int)(loss * tier.getCashbackRate());
        int balance   = p.getIsland() != null ? p.getIsland().getShells() : 0;
        int fisherLvl = p.getFishing() != null ? p.getFishing().getFishingLevel() : 1;

        return new SlotStateResponse(
                false, null,
                balance,
                p.getSlotFreeSpinsRemaining(),
                p.getSlotMultiplier(),
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
