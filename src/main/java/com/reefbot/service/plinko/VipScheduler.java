package com.reefbot.service.plinko;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Triggers VIP cashback payouts twice a week:
 * <ul>
 *   <li>Monday 00:00  — pays out the Thu–Sun period</li>
 *   <li>Thursday 00:00 — pays out the Mon–Wed period</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VipScheduler {

    private final VipService vipService;

    /** Runs at 00:00 every Monday (day-of-week = 1). */
    @Scheduled(cron = "0 0 0 * * MON")
    public void payoutMonday() {
        log.info("VIP cashback scheduled payout — Monday (Thu–Sun period)");
        vipService.processCashbacks();
    }

    /** Runs at 00:00 every Thursday (day-of-week = 4). */
    @Scheduled(cron = "0 0 0 * * THU")
    public void payoutThursday() {
        log.info("VIP cashback scheduled payout — Thursday (Mon–Wed period)");
        vipService.processCashbacks();
    }
}
