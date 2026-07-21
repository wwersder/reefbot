package com.reefbot.dto.slotwar;

import com.reefbot.entity.Player;
import com.reefbot.entity.PlayerSlotWarState;

public record SlotWarStateResponse(
        boolean onboardingRequired,
        String  message,
        int     balance,
        String  mode,
        int     freeSpinsRemaining,
        int     fsPendingWin,
        String  stickyColumnsJson
) {
    public static SlotWarStateResponse onboarding(String msg) {
        return new SlotWarStateResponse(true, msg, 0, "CALM", 0, 0, null);
    }

    public static SlotWarStateResponse ok(Player p) {
        int balance = p.getIsland() != null ? p.getIsland().getShells() : 0;
        PlayerSlotWarState sw = p.getSlotWarState();
        return new SlotWarStateResponse(
                false, null,
                balance,
                sw != null ? sw.getMode().name() : "CALM",
                sw != null ? sw.getFreeSpinsRemaining() : 0,
                sw != null ? sw.getFsPendingWin() : 0,
                sw != null ? sw.getStickyColumnsJson() : null
        );
    }
}
