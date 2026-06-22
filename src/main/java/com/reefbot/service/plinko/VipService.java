package com.reefbot.service.plinko;

import com.reefbot.entity.Island;
import com.reefbot.entity.Player;
import com.reefbot.enums.VipTier;
import com.reefbot.repository.IslandRepository;
import com.reefbot.repository.PlayerRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Handles VIP tier upgrades and bi-weekly cashback payouts.
 *
 * <p>Tier upgrade: permanent, based on cumulative lifetime wager.
 * Called after every game action that increases wager.
 *
 * <p>Cashback payout: scheduled Mon 00:00 and Thu 00:00.
 * Cashback = tier.cashbackRate * periodNetLoss (only if net loss > 0).
 * Winners in the period get nothing extra (preserves currency value).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VipService {

    private final PlayerRepository  playerRepository;
    private final IslandRepository  islandRepository;
    private final TelegramClient    telegramClient;

    // ── Tier upgrade ──────────────────────────────────────────────────────────

    /**
     * Checks whether the player qualifies for a higher VIP tier and upgrades
     * if so. Also initialises vipPeriodStart on first wager.
     *
     * <p><b>Does NOT save the player</b> — the caller is responsible for the save
     * so we avoid double-saves in play transactions.
     *
     * @return true if the tier was upgraded (so caller can notify the user)
     */
    public boolean updateTier(Player player) {
        VipTier earned = VipTier.forWager(
                player.getVipLifetimeWager() != null ? player.getVipLifetimeWager() : 0L);
        VipTier current = player.getVipTier() != null ? player.getVipTier() : VipTier.NONE;

        // Initialise period start if this is the first ever wager
        if (player.getVipPeriodStart() == null) {
            player.setVipPeriodStart(LocalDate.now());
        }

        if (earned.ordinal() > current.ordinal()) {
            player.setVipTier(earned);
            return true;
        }
        return false;
    }

    // ── Cashback payout ───────────────────────────────────────────────────────

    /**
     * Processes bi-weekly cashback for all eligible players.
     * Called by {@link VipScheduler} on Mon and Thu at 00:00.
     *
     * <p>Eligible = VIP tier CORAL+ AND vipPeriodNetLoss > 0 (net loss in the period).
     */
    @Transactional
    public void processCashbacks() {
        List<Player> candidates = playerRepository.findAll().stream()
                .filter(p -> p.getVipTier() != null && p.getVipTier() != VipTier.NONE)
                .filter(p -> p.getVipPeriodNetLoss() != null && p.getVipPeriodNetLoss() > 0)
                .toList();

        log.info("VIP cashback: processing {} eligible player(s)", candidates.size());

        for (Player player : candidates) {
            try {
                payCashback(player);
            } catch (Exception e) {
                log.error("VIP cashback failed for player {}", player.getId(), e);
            }
        }
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private void payCashback(Player player) {
        VipTier tier = player.getVipTier();
        long netLoss  = player.getVipPeriodNetLoss();
        long cashback = (long) Math.floor(netLoss * tier.getCashbackRate());

        if (cashback <= 0) {
            resetPeriod(player);
            return;
        }

        // Credit shells
        Island island = player.getIsland();
        if (island != null) {
            island.setShells(island.getShells() + (int) Math.min(cashback, Integer.MAX_VALUE));
            islandRepository.save(island);
        }

        log.info("VIP cashback: player {} | tier={} | netLoss={} | cashback={}",
                player.getId(), tier, netLoss, cashback);

        // Reset period counters
        resetPeriod(player);
        player.setVipCashbackPaidAt(LocalDateTime.now());
        playerRepository.save(player);

        // Notify player in Telegram
        sendCashbackNotification(player, tier, cashback, netLoss);
    }

    private void resetPeriod(Player player) {
        player.setVipPeriodNetLoss(0L);
        player.setVipPeriodStart(LocalDate.now());
    }

    private void sendCashbackNotification(Player player, VipTier tier, long cashback, long netLoss) {
        String text = String.format(
                "%s <b>VIP кешбэк %s!</b>\n\n" +
                "За прошедший период ты потерял <b>%d 🐚</b>\n" +
                "Кешбэк %d%% → <b>+%d 🐚</b> зачислено на счёт 🎁",
                tier.getEmoji(), tier.getDisplayName(),
                netLoss,
                (int) (tier.getCashbackRate() * 100),
                cashback);
        try {
            telegramClient.execute(SendMessage.builder()
                    .chatId(player.getTelegramId())
                    .text(text)
                    .parseMode("HTML")
                    .build());
        } catch (TelegramApiException e) {
            log.warn("Could not send cashback notification to player {}", player.getId(), e);
        }
    }
}
