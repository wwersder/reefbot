package com.reefbot.service.slot;

import com.reefbot.dto.slot.SlotSpinRequest;
import com.reefbot.dto.slot.SlotSpinResponse;
import com.reefbot.dto.slot.SlotStateResponse;
import com.reefbot.dto.slot.WinLine;
import com.reefbot.config.TelegramProperties;
import com.reefbot.entity.Island;
import com.reefbot.entity.Player;
import com.reefbot.entity.PlayerSlotState;
import com.reefbot.entity.SlotLog;
import com.reefbot.enums.OnboardingStep;
import com.reefbot.enums.PlayerStatus;
import com.reefbot.enums.SlotSymbol;
import com.reefbot.repository.IslandRepository;
import com.reefbot.repository.PlayerRepository;
import com.reefbot.repository.SlotLogRepository;
import com.reefbot.service.plinko.VipService;
import com.reefbot.util.TelegramInitDataVerifier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.reefbot.dto.slot.StickyWild;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class SlotService {

    // ── Constants ─────────────────────────────────────────────────────────────

    public static final int MIN_BET = 10;

    /**
     * 9 paylines on a 5×3 grid.
     * Each row defines the row-index [0=top, 1=mid, 2=bot] per reel (0..4).
     */
    private static final int[][] PAYLINES = {
        {1, 1, 1, 1, 1},  // 0 — middle row
        {0, 0, 0, 0, 0},  // 1 — top row
        {2, 2, 2, 2, 2},  // 2 — bottom row
        {0, 1, 2, 1, 0},  // 3 — V down
        {2, 1, 0, 1, 2},  // 4 — V up
        {0, 0, 1, 2, 2},  // 5 — step down
        {2, 2, 1, 0, 0},  // 6 — step up
        {1, 0, 1, 2, 1},  // 7 — zigzag 1
        {1, 2, 1, 0, 1},  // 8 — zigzag 2
    };

    // ── Symbol weight table (cumulative) ──────────────────────────────────────

    private static final SlotSymbol[] SYMBOLS = SlotSymbol.values();
    private static final double[]     CUM_WEIGHTS;

    static {
        CUM_WEIGHTS = new double[SYMBOLS.length];
        double total = 0;
        for (int i = 0; i < SYMBOLS.length; i++) {
            total += SYMBOLS[i].getWeight();
            CUM_WEIGHTS[i] = total;
        }
    }

    // ── Dependencies ──────────────────────────────────────────────────────────

    private final PlayerRepository    playerRepository;
    private final IslandRepository    islandRepository;
    private final SlotLogRepository   slotLogRepository;
    private final TelegramProperties  telegramProperties;
    private final VipService          vipService;
    private final SecureRandom        rng = new SecureRandom();

    /**
     * Bonus buy cost = bet × multiplier.
     * Per-payline SUM mechanic, 10 FS, wild mults ×1/×2/×3/×5.
     * Simulation-verified (1M sessions): E[FS] = 128.71×. Buy RTP = 128.71/134 = 96.1%.
     */
    public static final int BONUS_BUY_MULTIPLIER = 134;

    /**
     * Maximum win per free-spins session (× bet).
     * With per-payline SUM mechanic, E[FS]=128.71×, P99=1377×. Cap triggers in 0.21% of sessions.
     */
    public static final int MAX_WIN_MULTIPLIER = 2500;

    // Scatter trigger bonus pays (× bet), applied on top of free spins.
    private static final int SCATTER_PAY_3 = 5;
    private static final int SCATTER_PAY_4 = 15;
    private static final int SCATTER_PAY_5 = 50;

    // ── Public API ────────────────────────────────────────────────────────────

    public SlotStateResponse getState(String initData) {
        Player player = resolve(initData);
        if (!isReady(player)) {
            return SlotStateResponse.onboarding("Сначала заверши регистрацию в боте — /start");
        }
        return SlotStateResponse.ok(player);
    }

    @Transactional
    public SlotSpinResponse spin(String initData, SlotSpinRequest req) {
        Player player = resolve(initData);
        if (!isReady(player)) {
            return SlotSpinResponse.error("ONBOARDING_REQUIRED");
        }

        PlayerSlotState ss = ss(player);
        boolean inFreeSpins = ss.getFreeSpinsRemaining() > 0;

        Island island = islandRepository.findByPlayerForUpdate(player)
                .orElseThrow(() -> new SecurityException("Island not found for player " + player.getId()));

        // Validate bet (free spins cost nothing)
        if (!inFreeSpins) {
            if (req.bet() < MIN_BET)            return SlotSpinResponse.error("INVALID_BET");
            if (island.getShells() < req.bet()) return SlotSpinResponse.error("INSUFFICIENT_BALANCE");
        }

        int bet = req.bet();

        // Deduct bet
        if (!inFreeSpins) {
            island.setShells(island.getShells() - bet);
        }

        // ── Sticky wilds (Dog House mechanic) ────────────────────────────────
        List<StickyWild> stickyWilds = parseStickyWilds(ss.getStickyWildsJson());

        // ── Generate grid ─────────────────────────────────────────────────────
        SlotSymbol[][] grid = generateGrid();

        // During free spins: overlay existing sticky wilds (force WILD)
        if (inFreeSpins) {
            for (StickyWild sw : stickyWilds) {
                grid[sw.col()][sw.row()] = SlotSymbol.WILD;
            }
            // Detect new wilds (not already sticky) and stick them with a random mult
            for (int col = 0; col < 5; col++) {
                for (int row = 0; row < 3; row++) {
                    if (grid[col][row] == SlotSymbol.WILD && !isSticky(stickyWilds, col, row)) {
                        stickyWilds.add(new StickyWild(col, row, randomMult()));
                    }
                }
            }
        }

        // ── Check paylines ────────────────────────────────────────────────────
        // Dog House mechanic: per-payline multiplier = product of sticky wild mults
        // on that payline's winning chain. No global multiplier.
        List<WinLine> wins = checkPaylines(grid, bet, inFreeSpins ? stickyWilds : List.of());
        int totalWin = wins.stream().mapToInt(WinLine::amount).sum();

        // ── Check scatters ────────────────────────────────────────────────────
        int scatterCount = countSymbol(grid, SlotSymbol.SCATTER);
        boolean triggerFreeSpins = !inFreeSpins && scatterCount >= 3;

        int scatterAmount = 0;
        if (scatterCount >= 3) {
            scatterAmount = bet * switch (scatterCount) {
                case 3  -> SCATTER_PAY_3;
                case 4  -> SCATTER_PAY_4;
                default -> SCATTER_PAY_5;
            };
            totalWin += scatterAmount;
        }

        // ── Update free spins state ───────────────────────────────────────────
        if (triggerFreeSpins) {
            int bonusSpins = switch (scatterCount) {
                case 3  -> 10;
                case 4  -> 15;
                default -> 20;
            };
            ss.setFreeSpinsRemaining(bonusSpins);
            ss.setMultiplier(1);
            ss.setStickyWildsJson(null);
            ss.setFsPendingWin(0);
            stickyWilds = new ArrayList<>();
        } else if (inFreeSpins) {
            int remaining = ss.getFreeSpinsRemaining() - 1;
            ss.setFreeSpinsRemaining(Math.max(0, remaining));
            ss.setMultiplier(1); // no global multiplier in Dog House mechanic

            if (remaining <= 0) {
                ss.setStickyWildsJson(null);
                ss.setMultiplier(1);
                stickyWilds = new ArrayList<>();
            } else {
                ss.setStickyWildsJson(toStickyWildsJson(stickyWilds));
            }
        }

        // ── Credit winnings ───────────────────────────────────────────────────
        // During free spins: accumulate; credit all at round end.
        if (inFreeSpins) {
            ss.setFsPendingWin(ss.getFsPendingWin() + totalWin);
        } else {
            island.setShells(island.getShells() + totalWin);
        }

        // Capture running total for response (before possible reset)
        int fsPendingWin = ss.getFsPendingWin();

        // Last FS spin → flush accumulated win to balance (capped at MAX_WIN_MULTIPLIER × bet)
        if (inFreeSpins && ss.getFreeSpinsRemaining() == 0) {
            fsPendingWin = Math.min(fsPendingWin, bet * MAX_WIN_MULTIPLIER);
            island.setShells(island.getShells() + fsPendingWin);
            ss.setFsPendingWin(0);
        }
        islandRepository.save(island);

        // ── VIP tracking (only for paid spins) ───────────────────────────────
        if (!inFreeSpins) {
            player.setVipLifetimeWager(player.getVipLifetimeWager() + bet);
            int netLoss = bet - totalWin;
            player.setVipPeriodNetLoss(player.getVipPeriodNetLoss() + netLoss);
        }
        vipService.updateTier(player);
        playerRepository.save(player);

        // ── Persist log ───────────────────────────────────────────────────────
        slotLogRepository.save(SlotLog.builder()
                .player(player)
                .bet(bet)
                .win(totalWin)
                .scatterCount(scatterCount)
                .wasFreeSpin(inFreeSpins)
                .triggeredBonus(triggerFreeSpins)
                .gridJson(gridToJson(grid))
                .build());

        return SlotSpinResponse.ok(
                gridToNames(grid),
                wins,
                totalWin,
                scatterCount,
                scatterAmount,
                triggerFreeSpins,
                inFreeSpins,
                stickyWilds,
                fsPendingWin,
                player
        );
    }

    /** Buy 10 free spins for BONUS_BUY_MULTIPLIER × bet. */
    @Transactional
    public SlotSpinResponse buyBonus(String initData, SlotSpinRequest req) {
        Player player = resolve(initData);
        if (!isReady(player))                          return SlotSpinResponse.error("ONBOARDING_REQUIRED");
        if (ss(player).getFreeSpinsRemaining() > 0)   return SlotSpinResponse.error("ALREADY_IN_BONUS");
        if (req.bet() < MIN_BET)                       return SlotSpinResponse.error("INVALID_BET");

        int cost = req.bet() * BONUS_BUY_MULTIPLIER;

        Island island = islandRepository.findByPlayerForUpdate(player)
                .orElseThrow(() -> new SecurityException("Island not found for player " + player.getId()));

        if (island.getShells() < cost) return SlotSpinResponse.error("INSUFFICIENT_BALANCE");

        island.setShells(island.getShells() - cost);
        islandRepository.save(island);

        // VIP: bonus buy counts as wager
        player.setVipLifetimeWager(player.getVipLifetimeWager() + cost);
        player.setVipPeriodNetLoss(player.getVipPeriodNetLoss() + cost);

        PlayerSlotState ss = ss(player);
        ss.setFreeSpinsRemaining(10);
        ss.setMultiplier(1);
        ss.setStickyWildsJson(null);
        ss.setFsPendingWin(0);

        vipService.updateTier(player);
        playerRepository.save(player);

        return SlotSpinResponse.ok(new String[5][3], List.of(), 0, 0, 0, false, false, List.of(), 0, player);
    }

    // ── Grid generation ───────────────────────────────────────────────────────

    private SlotSymbol[][] generateGrid() {
        SlotSymbol[][] grid = new SlotSymbol[5][3];
        for (int reel = 0; reel < 5; reel++) {
            boolean reelHasScatter = false;
            for (int row = 0; row < 3; row++) {
                SlotSymbol sym = randomSymbol();
                // At most one scatter per reel — reroll until non-scatter
                if (sym == SlotSymbol.SCATTER && reelHasScatter) {
                    do { sym = randomSymbol(); } while (sym == SlotSymbol.SCATTER);
                }
                if (sym == SlotSymbol.SCATTER) reelHasScatter = true;
                grid[reel][row] = sym;
            }
        }
        return grid;
    }

    private SlotSymbol randomSymbol() {
        double r = rng.nextDouble();
        for (int i = 0; i < SYMBOLS.length; i++) {
            if (r < CUM_WEIGHTS[i]) return SYMBOLS[i];
        }
        return SYMBOLS[SYMBOLS.length - 1];
    }

    // ── Payline evaluation ────────────────────────────────────────────────────

    private List<WinLine> checkPaylines(SlotSymbol[][] grid, int bet, List<StickyWild> stickyWilds) {
        List<WinLine> wins = new ArrayList<>();
        for (int li = 0; li < PAYLINES.length; li++) {
            WinLine w = evaluateLine(grid, PAYLINES[li], li, bet, stickyWilds);
            if (w != null) wins.add(w);
        }
        return wins;
    }

    /**
     * Evaluates a single payline left-to-right.
     * WILD substitutes any paying symbol.
     * Target is the first non-WILD, non-SCATTER symbol encountered.
     * Returns null if fewer than 3 consecutive matches.
     *
     * Dog House mechanic: per-payline multiplier = product of sticky wild mults
     * for each WILD position within the winning chain (reels 0..count-1).
     * A non-sticky wild (stickyWilds is empty or wild not in list) contributes ×1.
     */
    private WinLine evaluateLine(SlotSymbol[][] grid, int[] rows, int lineIdx,
                                 int bet, List<StickyWild> stickyWilds) {
        SlotSymbol target = null;
        for (int reel = 0; reel < 5; reel++) {
            SlotSymbol s = grid[reel][rows[reel]];
            if (s.isPaying()) { target = s; break; }
        }
        if (target == null) return null;

        int count = 0;
        for (int reel = 0; reel < 5; reel++) {
            SlotSymbol s = grid[reel][rows[reel]];
            if (s == target || s.isWild()) count++;
            else break;
        }

        if (count < 3) return null;

        // Per-payline multiplier: sum of sticky wild mults within winning chain.
        // Dog House style: each wild on the winning payline adds its mult (×2 or ×3).
        // Non-sticky wild or no wilds → mult stays 1.
        int paylineMult = 0;
        for (int reel = 0; reel < count; reel++) {
            if (grid[reel][rows[reel]].isWild()) {
                int row = rows[reel];
                for (StickyWild sw : stickyWilds) {
                    if (sw.col() == reel && sw.row() == row) {
                        paylineMult += sw.mult();
                        break;
                    }
                }
            }
        }
        if (paylineMult == 0) paylineMult = 1;

        double payoutMult = target.getPayout(count) * paylineMult;
        int amount = (int)(bet * payoutMult);
        if (amount <= 0) return null;

        return new WinLine(lineIdx, target.name(), count, amount, rows.clone());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Returns the player's slot state, creating a default row if not yet present.
     * New players don't have a row until their first slot interaction.
     */
    private PlayerSlotState ss(Player player) {
        if (player.getSlotState() == null) {
            PlayerSlotState fresh = PlayerSlotState.builder().player(player).build();
            player.setSlotState(fresh);
        }
        return player.getSlotState();
    }

    private int countSymbol(SlotSymbol[][] grid, SlotSymbol sym) {
        int n = 0;
        for (SlotSymbol[] reel : grid)
            for (SlotSymbol s : reel)
                if (s == sym) n++;
        return n;
    }

    private String[][] gridToNames(SlotSymbol[][] grid) {
        String[][] names = new String[5][3];
        for (int r = 0; r < 5; r++)
            for (int row = 0; row < 3; row++)
                names[r][row] = grid[r][row].name();
        return names;
    }

    private String gridToJson(SlotSymbol[][] grid) {
        StringBuilder sb = new StringBuilder("[");
        for (int r = 0; r < 5; r++) {
            sb.append('[');
            for (int row = 0; row < 3; row++) {
                sb.append('"').append(grid[r][row].name()).append('"');
                if (row < 2) sb.append(',');
            }
            sb.append(']');
            if (r < 4) sb.append(',');
        }
        sb.append(']');
        return sb.toString();
    }

    // ── Sticky wilds helpers ──────────────────────────────────────────────────

    /** Wild multiplier: ×1 50%, ×2 30%, ×3 15%, ×5 5%. E[mult] = 1.80. */
    private int randomMult() {
        double r = rng.nextDouble();
        if (r < 0.50) return 1;
        if (r < 0.80) return 2;
        if (r < 0.95) return 3;
        return 5;
    }

    private boolean isSticky(List<StickyWild> list, int col, int row) {
        return list.stream().anyMatch(sw -> sw.col() == col && sw.row() == row);
    }

    /** Parse [[col,row,mult],...] JSON into list. Returns empty list on null/error. */
    private List<StickyWild> parseStickyWilds(String json) {
        List<StickyWild> result = new ArrayList<>();
        if (json == null || json.isBlank() || json.equals("[]")) return result;
        try {
            String inner = json.trim().substring(1, json.trim().length() - 1);
            if (inner.isBlank()) return result;
            String[] parts = inner.split("],\\s*\\[");
            for (String part : parts) {
                part = part.replace("[", "").replace("]", "").trim();
                String[] nums = part.split(",");
                if (nums.length >= 3) {
                    int col  = Integer.parseInt(nums[0].trim());
                    int row  = Integer.parseInt(nums[1].trim());
                    int mult = Integer.parseInt(nums[2].trim());
                    result.add(new StickyWild(col, row, mult));
                }
            }
        } catch (Exception e) {
            log.warn("Failed to parse stickyWildsJson: {}", json);
        }
        return result;
    }

    /** Serialize sticky wilds to [[col,row,mult],...] JSON. */
    private String toStickyWildsJson(List<StickyWild> list) {
        if (list.isEmpty()) return "[]";
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < list.size(); i++) {
            StickyWild sw = list.get(i);
            sb.append('[').append(sw.col()).append(',')
              .append(sw.row()).append(',').append(sw.mult()).append(']');
            if (i < list.size() - 1) sb.append(',');
        }
        sb.append(']');
        return sb.toString();
    }

    private Player resolve(String initData) {
        var params = TelegramInitDataVerifier.verify(initData, telegramProperties.getToken());
        Long tgId  = TelegramInitDataVerifier.extractTelegramId(params);
        return playerRepository.findByTelegramId(tgId)
                .orElseThrow(() -> new SecurityException("Player not found: " + tgId));
    }

    private boolean isReady(Player p) {
        return p.getStatus() == PlayerStatus.ACTIVE
               && (p.getOnboardingStep() == null
                   || p.getOnboardingStep() == OnboardingStep.FINISHED);
    }
}
