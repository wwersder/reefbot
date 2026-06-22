package com.reefbot.dto.plinko;

import com.reefbot.entity.Player;
import com.reefbot.enums.VipTier;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * Response for GET /api/mini/plinko/state
 */
public record PlinkoStateResponse(
        boolean onboardingRequired,
        String message,

        // Present only when onboardingRequired=false
        Integer balance,
        Integer dailyLost,
        Integer dailyLimitRemaining,
        Integer fisherLevel,
        boolean rows8Unlocked,
        boolean rows12Unlocked,

        // VIP info
        String  vipTier,
        String  vipTierLabel,
        String  vipTierEmoji,
        Integer vipCashbackPercent,
        Long    vipLifetimeWager,
        Long    vipPeriodNetLoss,
        Long    vipEstimatedCashback,
        Long    vipNextTierThreshold,
        String  vipNextCashbackDate
) {
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd.MM");

    /** Factory for onboarding-blocked response. */
    public static PlinkoStateResponse blocked() {
        return new PlinkoStateResponse(
                true,
                "Сначала заверши регистрацию в боте — напиши /start",
                null, null, null, null, false, false,
                null, null, null, null, null, null, null, null, null
        );
    }

    /** Factory for normal state response. */
    public static PlinkoStateResponse ok(int balance, int dailyLost, int fisherLevel, Player player) {
        int remaining   = Math.max(0, 2000 - dailyLost);
        VipTier tier    = player.getVipTier() != null ? player.getVipTier() : VipTier.NONE;
        long wager      = player.getVipLifetimeWager() != null ? player.getVipLifetimeWager() : 0L;
        long periodLoss    = player.getVipPeriodNetLoss() != null ? player.getVipPeriodNetLoss() : 0L;
        // Only net losses are cashback-eligible
        long effectiveLoss = Math.max(0L, periodLoss);
        long estimated     = (long) Math.floor(effectiveLoss * tier.getCashbackRate());

        VipTier next = tier.next();
        Long nextThreshold = next != null ? next.getWageredThreshold() : null;

        return new PlinkoStateResponse(
                false, null,
                balance, dailyLost, remaining,
                fisherLevel,
                true,
                fisherLevel >= 5,
                // VIP
                tier.name(),
                tier.label(),
                tier.getEmoji(),
                (int) (tier.getCashbackRate() * 100),
                wager,
                effectiveLoss,
                estimated,
                nextThreshold,
                nextCashbackDate()
        );
    }

    /** Returns the date string of the next scheduled cashback payout (Mon or Thu). */
    private static String nextCashbackDate() {
        LocalDate today = LocalDate.now();
        DayOfWeek dow   = today.getDayOfWeek();
        // Next payout: Thursday if we're Mon–Wed, otherwise next Monday
        LocalDate next;
        if (dow == DayOfWeek.MONDAY || dow == DayOfWeek.TUESDAY || dow == DayOfWeek.WEDNESDAY) {
            next = today.with(DayOfWeek.THURSDAY);
        } else if (dow == DayOfWeek.THURSDAY) {
            // Pay day itself — next Monday
            next = today.plusDays(4);
        } else {
            next = today.with(java.time.temporal.TemporalAdjusters.next(DayOfWeek.MONDAY));
        }
        return next.format(DATE_FMT);
    }
}
