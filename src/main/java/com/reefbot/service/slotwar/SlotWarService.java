package com.reefbot.service.slotwar;

import com.reefbot.config.TelegramProperties;
import com.reefbot.dto.slotwar.*;
import com.reefbot.entity.*;
import com.reefbot.enums.*;
import com.reefbot.repository.*;
import com.reefbot.util.TelegramInitDataVerifier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.*;

/**
 * Core logic for the «Шторм vs Штиль» 5×5 expanding-wild slot.
 *
 * <p>Mechanic overview:
 * <ol>
 *   <li>Generate a 5×5 grid. Symbol weights differ by mode (CALM / STORM).</li>
 *   <li>In free-spins, restore sticky-expanded columns before new symbols.</li>
 *   <li>For each column that contains a WILD AND participates in a 3+ payline win
 *       → expand that column (all 5 rows become WILD) and roll a random multiplier.</li>
 *   <li>Re-evaluate all 15 paylines on the expanded grid.</li>
 *   <li>finalWin = paylineWin × max(1, sum-of-all-expanded-multipliers).</li>
 *   <li>In free-spins, newly expanded columns become sticky (persist + accumulate mults).</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SlotWarService {

    public static final int MIN_BET = 10;

    // ── 15 paylines on 5×5 grid (row index per column, row 0=top, row 4=bottom) ─

    private static final int[][] PAYLINES = {
        {0, 0, 0, 0, 0},  //  0 top row
        {2, 2, 2, 2, 2},  //  1 middle row
        {4, 4, 4, 4, 4},  //  2 bottom row
        {1, 1, 1, 1, 1},  //  3 row 1
        {3, 3, 3, 3, 3},  //  4 row 3
        {0, 1, 2, 3, 4},  //  5 diagonal ↘
        {4, 3, 2, 1, 0},  //  6 diagonal ↗
        {0, 0, 1, 0, 0},  //  7 V-top
        {4, 4, 3, 4, 4},  //  8 V-bottom
        {2, 1, 0, 1, 2},  //  9 peak-up
        {2, 3, 4, 3, 2},  // 10 peak-down
        {1, 0, 1, 2, 1},  // 11 zigzag
        {3, 4, 3, 2, 3},  // 12 zigzag-2
        {0, 1, 2, 1, 0},  // 13 W-top
        {4, 3, 2, 3, 4},  // 14 W-bottom
    };

    // ── Paying symbols in weight order (WILD & SCATTER handled separately) ─────

    private static final SlotWarSymbol[] PAYING = {
        SlotWarSymbol.JELLYFISH,
        SlotWarSymbol.SHELL,
        SlotWarSymbol.CRAB,
        SlotWarSymbol.FISH,
        SlotWarSymbol.TURTLE,
        SlotWarSymbol.OCTOPUS,
        SlotWarSymbol.SHARK,
        SlotWarSymbol.DOLPHIN
    };

    // Base weights for paying symbols (same for both modes)
    private static final int[] BASE_WEIGHTS = {22, 18, 15, 12, 10, 8, 6, 4}; // sum = 95

    // WILD weight by mode (out of 100 total)
    private static final int WILD_WEIGHT_CALM  = 4;   // ~4% per cell ≈ 18.5% per column
    private static final int WILD_WEIGHT_STORM = 2;   // ~2% per cell ≈ 9.6%  per column

    // SCATTER weight on eligible columns (0, 2, 4); total weight still 100
    // Scatter replaces some paying-symbol weight. Probability: 1% per eligible cell.
    private static final int SCATTER_WEIGHT = 1;

    // ── Multiplier tables (weighted draw, indices correspond to MULT_VALUES) ───

    private static final int[]   MULT_VALUES = { 2,  3,  5,  10,  25,  50, 100, 250};

    // CALM: biased toward lower multipliers
    private static final int[]   MULT_W_CALM  = {30, 22, 17,  12,   8,   5,   4,   2}; // sum=100
    // STORM: biased toward higher multipliers
    private static final int[]   MULT_W_STORM = {10, 10, 15,  20,  20,  13,   9,   3}; // sum=100

    // During free spins: lower floor, higher ceiling
    private static final int[]   MULT_W_CALM_FS  = {0, 10, 25, 28, 18,  10,   6,   3}; // sum=100
    private static final int[]   MULT_W_STORM_FS = {0,  0,  8, 22, 28,  22,  14,   6}; // sum=100

    // ── Dependencies ─────────────────────────────────────────────────────────────

    private final PlayerRepository           playerRepository;
    private final IslandRepository           islandRepository;
    private final SlotWarLogRepository       slotWarLogRepository;
    private final TelegramProperties         telegramProperties;
    private final SecureRandom               rng = new SecureRandom();

    // ── Public API ────────────────────────────────────────────────────────────────

    public SlotWarStateResponse getState(String initData) {
        Player player = resolve(initData);
        if (!isReady(player)) {
            return SlotWarStateResponse.onboarding("Сначала заверши регистрацию в боте — /start");
        }
        return SlotWarStateResponse.ok(player);
    }

    @Transactional
    public SlotWarSpinResponse spin(String initData, SlotWarSpinRequest req) {
        Player player = resolve(initData);
        if (!isReady(player)) return SlotWarSpinResponse.error("ONBOARDING_REQUIRED");

        PlayerSlotWarState sw = ensureState(player);
        boolean inFreeSpins = sw.getFreeSpinsRemaining() > 0;

        Island island = islandRepository.findByPlayerForUpdate(player)
                .orElseThrow(() -> new IllegalStateException("Island not found"));

        // Validate & deduct bet
        if (!inFreeSpins) {
            if (req.bet() < MIN_BET)             return SlotWarSpinResponse.error("INVALID_BET");
            if (island.getShells() < req.bet())  return SlotWarSpinResponse.error("INSUFFICIENT_BALANCE");
            island.setShells(island.getShells() - req.bet());
        }

        // Persist mode choice from client (only if not in free spins)
        SlotWarMode mode;
        try {
            mode = SlotWarMode.valueOf(req.mode().toUpperCase());
        } catch (Exception e) {
            mode = SlotWarMode.CALM;
        }
        if (!inFreeSpins) sw.setMode(mode);
        else mode = sw.getMode(); // mode locked during bonus

        int bet = req.bet();

        // ── 1. Parse sticky columns ───────────────────────────────────────────────
        List<int[]> stickyColumns = parseSticky(sw.getStickyColumnsJson());

        // ── 2. Generate grid ──────────────────────────────────────────────────────
        SlotWarSymbol[][] grid = generateGrid(mode);

        // ── 3. Apply sticky (free spins) — expand those columns ──────────────────
        Set<Integer> stickyColSet = new HashSet<>();
        for (int[] sc : stickyColumns) {
            expandColumn(grid, sc[0]);
            stickyColSet.add(sc[0]);
        }

        // ── 4. Identify columns with wilds (not already sticky/expanded) ──────────
        List<Integer> wildCols = new ArrayList<>();
        for (int col = 0; col < 5; col++) {
            if (stickyColSet.contains(col)) continue;
            for (int row = 0; row < 5; row++) {
                if (grid[col][row] == SlotWarSymbol.WILD) {
                    wildCols.add(col);
                    break;
                }
            }
        }

        // ── 5. Expand wilds that participate in a win ─────────────────────────────
        List<ExpandedWild> expandedWilds = new ArrayList<>();
        for (int wc : wildCols) {
            if (wildParticipatesInWin(grid, wc)) {
                int mult = rollMultiplier(mode, inFreeSpins);
                expandColumn(grid, wc);
                expandedWilds.add(new ExpandedWild(wc, mult));
            }
        }

        // ── 6. Evaluate paylines on expanded grid ─────────────────────────────────
        List<WinLineWar> winLines = checkPaylines(grid, bet);
        int paylineWin = winLines.stream().mapToInt(WinLineWar::amount).sum();

        // ── 7. Check scatter (only on cols 0, 2, 4) ──────────────────────────────
        int scatterCount = countScatters(grid);
        boolean bonusTriggered = !inFreeSpins && scatterCount == 3;

        // ── 8. Calculate total multiplier ─────────────────────────────────────────
        int stickyMult = stickyColumns.stream().mapToInt(sc -> sc[1]).sum();
        int newMult    = expandedWilds.stream().mapToInt(ExpandedWild::mult).sum();
        int totalMult  = Math.max(1, stickyMult + newMult);

        // ── 9. Final win ──────────────────────────────────────────────────────────
        // Cap at ×25 000 per bet to match Zeus vs Hades spec
        long rawWin = (long) paylineWin * totalMult;
        int finalWin = (int) Math.min(rawWin, (long) bet * 25_000);

        // ── 10. Update free-spins state ───────────────────────────────────────────
        if (bonusTriggered) {
            sw.setFreeSpinsRemaining(10);
            sw.setStickyColumnsJson(null);
            sw.setFsPendingWin(0);
            stickyColumns = new ArrayList<>();
        } else if (inFreeSpins) {
            // Add new expansions to sticky
            for (ExpandedWild ew : expandedWilds) {
                stickyColumns.add(new int[]{ew.col(), ew.mult()});
            }
            int remaining = sw.getFreeSpinsRemaining() - 1;
            sw.setFreeSpinsRemaining(Math.max(0, remaining));
            if (remaining <= 0) {
                // Flush at FS end — no, accumulate and flush below
                sw.setStickyColumnsJson(null);
            } else {
                sw.setStickyColumnsJson(toStickyJson(stickyColumns));
            }
        }

        // ── 11. Credit winnings ───────────────────────────────────────────────────
        if (inFreeSpins) {
            sw.setFsPendingWin(sw.getFsPendingWin() + finalWin);
        } else {
            island.setShells(island.getShells() + finalWin);
        }

        int fsPendingWin = sw.getFsPendingWin();

        // Last FS spin: flush accumulated win
        if (inFreeSpins && sw.getFreeSpinsRemaining() == 0) {
            island.setShells(island.getShells() + fsPendingWin);
            sw.setFsPendingWin(0);
            fsPendingWin = 0; // reset for response (already credited)
        }

        islandRepository.save(island);
        playerRepository.save(player);

        // ── 12. Persist log ───────────────────────────────────────────────────────
        slotWarLogRepository.save(SlotWarLog.builder()
                .player(player)
                .mode(mode)
                .bet(bet)
                .paylineWin(paylineWin)
                .totalMult(totalMult)
                .finalWin(finalWin)
                .expandedCount(expandedWilds.size())
                .bonusTriggered(bonusTriggered)
                .wasFreeSpin(inFreeSpins)
                .build());

        return SlotWarSpinResponse.ok(
                gridToNames(grid),
                expandedWilds,
                winLines,
                paylineWin,
                totalMult,
                finalWin,
                scatterCount,
                bonusTriggered,
                inFreeSpins,
                sw,
                island.getShells(),
                mode.name()
        );
    }

    // ── Grid generation ───────────────────────────────────────────────────────────

    /**
     * Generates a 5×5 grid with mode-appropriate symbol weights.
     * Scatter appears only on columns 0, 2, 4.
     * At most one WILD or SCATTER per column (per natural odds — no forced limit).
     */
    private SlotWarSymbol[][] generateGrid(SlotWarMode mode) {
        int wildWeight = (mode == SlotWarMode.CALM) ? WILD_WEIGHT_CALM : WILD_WEIGHT_STORM;
        SlotWarSymbol[][] grid = new SlotWarSymbol[5][5];

        for (int col = 0; col < 5; col++) {
            boolean scatterEligible = (col == 0 || col == 2 || col == 4);
            for (int row = 0; row < 5; row++) {
                grid[col][row] = randomSymbol(wildWeight, scatterEligible);
            }
        }
        return grid;
    }

    private SlotWarSymbol randomSymbol(int wildWeight, boolean scatterEligible) {
        // Total weight: BASE_WEIGHTS.sum (95) + wildWeight + (scatterEligible ? SCATTER_WEIGHT : 0)
        int scatterW = scatterEligible ? SCATTER_WEIGHT : 0;
        int total    = 95 + wildWeight + scatterW;
        int roll     = rng.nextInt(total);

        int cumulative = 0;
        for (int i = 0; i < BASE_WEIGHTS.length; i++) {
            cumulative += BASE_WEIGHTS[i];
            if (roll < cumulative) return PAYING[i];
        }
        cumulative += wildWeight;
        if (roll < cumulative) return SlotWarSymbol.WILD;
        return SlotWarSymbol.SCATTER;
    }

    // ── Wild expansion helpers ────────────────────────────────────────────────────

    private void expandColumn(SlotWarSymbol[][] grid, int col) {
        for (int row = 0; row < 5; row++) {
            grid[col][row] = SlotWarSymbol.WILD;
        }
    }

    /**
     * Checks whether a wild in {@code wildCol} participates in any winning payline.
     * A column's wild participates if the specific cell used by a payline is WILD
     * and that payline has a 3+ consecutive match from the left.
     */
    private boolean wildParticipatesInWin(SlotWarSymbol[][] grid, int wildCol) {
        for (int[] payline : PAYLINES) {
            if (!grid[wildCol][payline[wildCol]].isWild()) continue;

            // Find target (first non-wild paying symbol from left)
            SlotWarSymbol target = null;
            for (int col = 0; col < 5; col++) {
                SlotWarSymbol s = grid[col][payline[col]];
                if (s.isPaying()) { target = s; break; }
            }
            if (target == null) continue;

            // Count consecutive matches left→right
            int count = 0;
            for (int col = 0; col < 5; col++) {
                SlotWarSymbol s = grid[col][payline[col]];
                if (s == target || s.isWild()) count++;
                else break;
            }

            if (count >= 3 && wildCol < count) return true;
        }
        return false;
    }

    // ── Payline evaluation ────────────────────────────────────────────────────────

    private List<WinLineWar> checkPaylines(SlotWarSymbol[][] grid, int bet) {
        List<WinLineWar> wins = new ArrayList<>();
        for (int li = 0; li < PAYLINES.length; li++) {
            WinLineWar w = evaluateLine(grid, PAYLINES[li], li, bet);
            if (w != null) wins.add(w);
        }
        return wins;
    }

    private WinLineWar evaluateLine(SlotWarSymbol[][] grid, int[] rows, int lineIdx, int bet) {
        // Find target symbol (first paying, non-WILD from left)
        SlotWarSymbol target = null;
        for (int col = 0; col < 5; col++) {
            SlotWarSymbol s = grid[col][rows[col]];
            if (s.isPaying()) { target = s; break; }
        }
        if (target == null) return null;

        // Count consecutive matches
        int count = 0;
        for (int col = 0; col < 5; col++) {
            SlotWarSymbol s = grid[col][rows[col]];
            if (s == target || s.isWild()) count++;
            else break;
        }

        if (count < 3) return null;

        int amount = (int)(bet * target.getPayout(count));
        if (amount <= 0) return null;
        return new WinLineWar(lineIdx, target.name(), count, amount, rows.clone());
    }

    // ── Scatter ───────────────────────────────────────────────────────────────────

    /** Counts scatters on cols 0, 2, 4 (one per eligible column max). */
    private int countScatters(SlotWarSymbol[][] grid) {
        int count = 0;
        for (int col : new int[]{0, 2, 4}) {
            for (int row = 0; row < 5; row++) {
                if (grid[col][row] == SlotWarSymbol.SCATTER) { count++; break; }
            }
        }
        return count;
    }

    // ── Multiplier roll ───────────────────────────────────────────────────────────

    private int rollMultiplier(SlotWarMode mode, boolean freeSpins) {
        int[] weights = freeSpins
                ? (mode == SlotWarMode.CALM ? MULT_W_CALM_FS  : MULT_W_STORM_FS)
                : (mode == SlotWarMode.CALM ? MULT_W_CALM      : MULT_W_STORM);

        int total = Arrays.stream(weights).sum();
        int roll  = rng.nextInt(total);
        int cum   = 0;
        for (int i = 0; i < weights.length; i++) {
            cum += weights[i];
            if (roll < cum) return MULT_VALUES[i];
        }
        return MULT_VALUES[MULT_VALUES.length - 1];
    }

    // ── JSON helpers for sticky columns [[col,mult],...] ─────────────────────────

    private List<int[]> parseSticky(String json) {
        List<int[]> result = new ArrayList<>();
        if (json == null || json.isBlank() || "[]".equals(json.trim())) return result;
        try {
            String inner = json.trim().substring(1, json.trim().length() - 1).trim();
            if (inner.isEmpty()) return result;
            for (String part : inner.split("],\\s*\\[")) {
                part = part.replace("[", "").replace("]", "").trim();
                String[] nums = part.split(",");
                if (nums.length >= 2) {
                    result.add(new int[]{
                        Integer.parseInt(nums[0].trim()),
                        Integer.parseInt(nums[1].trim())
                    });
                }
            }
        } catch (Exception e) {
            log.warn("Failed to parse stickyColumnsJson: {}", json);
        }
        return result;
    }

    private String toStickyJson(List<int[]> list) {
        if (list.isEmpty()) return "[]";
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < list.size(); i++) {
            sb.append('[').append(list.get(i)[0]).append(',').append(list.get(i)[1]).append(']');
            if (i < list.size() - 1) sb.append(',');
        }
        return sb.append(']').toString();
    }

    // ── Grid serialization ────────────────────────────────────────────────────────

    private String[][] gridToNames(SlotWarSymbol[][] grid) {
        String[][] names = new String[5][5];
        for (int col = 0; col < 5; col++)
            for (int row = 0; row < 5; row++)
                names[col][row] = grid[col][row].name();
        return names;
    }

    // ── State helpers ─────────────────────────────────────────────────────────────

    private PlayerSlotWarState ensureState(Player player) {
        if (player.getSlotWarState() == null) {
            PlayerSlotWarState sw = PlayerSlotWarState.builder().player(player).build();
            player.setSlotWarState(sw);
        }
        return player.getSlotWarState();
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
