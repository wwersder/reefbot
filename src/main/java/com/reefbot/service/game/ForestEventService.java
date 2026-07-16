package com.reefbot.service.game;

import com.reefbot.dto.BotResponse;
import com.reefbot.entity.Island;
import com.reefbot.entity.Player;
import com.reefbot.entity.PlayerForest;
import com.reefbot.enums.HuntingSpot;
import com.reefbot.repository.IslandRepository;
import com.reefbot.repository.PlayerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Manages two forest events that fire while hunting is active:
 *
 * <p><b>Patrol</b> — analog of beach scan: 4-hour cooldown, gives meat/fur on click.</p>
 *
 * <p><b>Beast Sighting + Trap</b> — analog of tide, but with deferred reveal:
 * <ol>
 *   <li>Fires 40–80 min after hunt start; 25-minute window.</li>
 *   <li>Player sees "🦌 Зверь рядом!" button on the active hunt screen.</li>
 *   <li>Pressing it shows an inline keyboard with 3 trap locations (different odds/rewards).</li>
 *   <li>Player picks a location — choice stored in {@code sightingTrapChoice}.</li>
 *   <li>At collect time, {@link HuntingService#collectYield} rolls the trap and reveals the result.</li>
 * </ol>
 * This creates genuine anticipation: the player makes a strategic decision mid-hunt
 * and waits hours to see if it paid off.
 * </p>
 */
@Service
@RequiredArgsConstructor
public class ForestEventService {

    private static final int PATROL_COOLDOWN_HOURS       = 4;
    private static final int SIGHTING_WINDOW_MINUTES     = 25;
    private static final int SIGHTING_DELAY_MIN_MINUTES  = 40;
    private static final int SIGHTING_DELAY_MAX_MINUTES  = 80;

    // ── Trap options ──────────────────────────────────────────────────────────

    /**
     * One trap placement option shown to the player as an inline button.
     *
     * @param locationName  display name of the location
     * @param emoji         location emoji
     * @param chance        success probability 0–100
     * @param meatBonus     meat added on success
     * @param furBonus      fur added on success
     */
    public record TrapOption(String locationName, String emoji, int chance, int meatBonus, int furBonus) {
        /** Label shown on the inline button. */
        public String buttonLabel() {
            StringBuilder sb = new StringBuilder(emoji).append(" ").append(locationName)
                    .append(" — ").append(chance).append("%, +").append(meatBonus).append(" 🥩");
            if (furBonus > 0) sb.append(" +").append(furBonus).append(" 🪶");
            return sb.toString();
        }
    }

    // Three trap options per spot, ordered reliable → medium → risky

    private static final List<TrapOption> TRAPS_EDGE = List.of(
            new TrapOption("У ручья",  "🏞", 55, 4, 0),   // reliable
            new TrapOption("На тропе", "🌲", 35, 7, 0),   // medium
            new TrapOption("У норы",   "🕳", 20, 4, 2)    // risky, adds fur
    );

    private static final List<TrapOption> TRAPS_DEEP = List.of(
            new TrapOption("У водопоя",  "🌊", 50, 6, 0),  // reliable
            new TrapOption("В чаще",     "🌿", 30, 9, 1),  // medium
            new TrapOption("В буреломе", "🪵", 20, 6, 3)   // risky, fur
    );

    private static final List<TrapOption> TRAPS_WILD = List.of(
            new TrapOption("На склоне",  "⛰",  45,  8, 0),  // reliable
            new TrapOption("В ущелье",   "🌑",  25, 12, 2),  // risky
            new TrapOption("У берлоги",  "🐻",  15,  8, 5)   // very risky, high fur
    );

    /** Returns the 3 trap options for a given spot. Public static — used by HuntingService. */
    public static List<TrapOption> trapPool(HuntingSpot spot) {
        return switch (spot) {
            case EDGE -> TRAPS_EDGE;
            case DEEP -> TRAPS_DEEP;
            case WILD -> TRAPS_WILD;
        };
    }

    // ── Patrol narratives ─────────────────────────────────────────────────────

    private static final List<String> PATROL_SMALL = List.of(
            "Прошёлся по краю угодья. Следов немного, но что-то всё же нашлось.",
            "Под старой корягой — запасы чьей-то кладовой.",
            "Ягоды и пара следов. Мелочь, но пригодится.",
            "Осмотрелся вокруг. Лес спокоен, кое-что прихватил по пути."
    );

    private static final List<String> PATROL_MEDIUM = List.of(
            "Свежие следы у ручья. Поставил несколько силков — пришли кое с чем.",
            "Нашёл поляну с дичью — небольшой улов в кармане.",
            "Лесная тропа привела к добыче. Удача небольшая, но есть."
    );

    private static final List<String> PATROL_RARE = List.of(
            "🐺 Нашёл старое убежище охотника — там оказались запасы!",
            "🦌 Напал на хороший след. Вернулся с богатой добычей.",
            "🪶 Редкая удача — дичь сама вышла навстречу."
    );

    private final IslandRepository islandRepository;
    private final PlayerRepository playerRepository;

    // ── Patrol ────────────────────────────────────────────────────────────────

    public boolean isPatrolReady(Player player) {
        LocalDateTime scanned = player.getForest().getPatrolScannedAt();
        return scanned == null
                || LocalDateTime.now().isAfter(scanned.plusHours(PATROL_COOLDOWN_HOURS));
    }

    public String patrolCooldownText(Player player) {
        LocalDateTime scanned = player.getForest().getPatrolScannedAt();
        if (scanned == null) return "готово";
        LocalDateTime readyAt = scanned.plusHours(PATROL_COOLDOWN_HOURS);
        long total   = Math.max(0, java.time.Duration.between(LocalDateTime.now(), readyAt).getSeconds());
        long hours   = total / 3600;
        long minutes = (total % 3600) / 60;
        if (hours > 0) return "через " + hours + " ч " + minutes + " мин";
        return "через " + minutes + " мин";
    }

    @Transactional
    public PatrolResult scanPatrol(Player player, Island island) {
        player.getForest().setPatrolScannedAt(LocalDateTime.now());

        int roll = ThreadLocalRandom.current().nextInt(100);
        int meat, fur;
        boolean rare;
        String flavor;

        if (roll < 5) {           // 5% — редкость
            meat   = 8 + ThreadLocalRandom.current().nextInt(5); // 8–12
            fur    = 2;
            rare   = true;
            flavor = PATROL_RARE.get(ThreadLocalRandom.current().nextInt(PATROL_RARE.size()));
        } else if (roll < 20) {   // 15% — среднее
            meat   = 4 + ThreadLocalRandom.current().nextInt(5); // 4–8
            fur    = 1;
            rare   = false;
            flavor = PATROL_MEDIUM.get(ThreadLocalRandom.current().nextInt(PATROL_MEDIUM.size()));
        } else {                   // 80% — мелочь
            meat   = 2 + ThreadLocalRandom.current().nextInt(3); // 2–4
            fur    = 0;
            rare   = false;
            flavor = PATROL_SMALL.get(ThreadLocalRandom.current().nextInt(PATROL_SMALL.size()));
        }

        int storageLeft = island.getStorageCapacity() - totalStored(island);
        int meatActual  = Math.min(meat, Math.max(0, storageLeft));
        int furActual   = Math.min(fur,  Math.max(0, storageLeft - meatActual));

        island.setMeat(island.getMeat() + meatActual);
        island.setFur(island.getFur() + furActual);
        islandRepository.save(island);
        playerRepository.save(player);

        return new PatrolResult(meatActual, furActual, rare, flavor);
    }

    // ── Beast Sighting ────────────────────────────────────────────────────────

    public boolean isSightingActive(Player player) {
        PlayerForest forest = player.getForest();
        if (forest.getSightingAvailableAt() == null || forest.getSightingExpiresAt() == null) return false;
        if (Boolean.TRUE.equals(forest.getSightingClaimed())) return false;
        LocalDateTime now = LocalDateTime.now();
        return !now.isBefore(forest.getSightingAvailableAt())
                && now.isBefore(forest.getSightingExpiresAt());
    }

    /**
     * Schedule a beast sighting to fire 40–80 min after hunt start.
     * Does NOT save — caller must save player.
     */
    public void scheduleSighting(Player player) {
        int delay = SIGHTING_DELAY_MIN_MINUTES
                + ThreadLocalRandom.current().nextInt(
                        SIGHTING_DELAY_MAX_MINUTES - SIGHTING_DELAY_MIN_MINUTES + 1);
        LocalDateTime available = LocalDateTime.now().plusMinutes(delay);
        PlayerForest forest = player.getForest();
        forest.setSightingAvailableAt(available);
        forest.setSightingExpiresAt(available.plusMinutes(SIGHTING_WINDOW_MINUTES));
        forest.setSightingClaimed(false);
        forest.setSightingNotified(false);
        forest.setSightingTrapChoice(null);
    }

    /**
     * Builds the inline trap-placement message shown when player presses "🦌 Зверь рядом!".
     * Each of the 3 buttons sends a {@code "forest_trap:{idx}"} callback.
     */
    public BotResponse buildTrapChoiceScreen(HuntingSpot spot) {
        List<TrapOption> traps = trapPool(spot);

        String text = "🦌 <b>Зверь замечен!</b>\n\n"
                + "Выбери место для ловушки.\n"
                + "Результат узнаешь когда вернёшься с охоты.";

        InlineKeyboardMarkup kb = InlineKeyboardMarkup.builder()
                .keyboardRow(new InlineKeyboardRow(
                        InlineKeyboardButton.builder()
                                .text(traps.get(0).buttonLabel())
                                .callbackData("forest_trap:0")
                                .build()))
                .keyboardRow(new InlineKeyboardRow(
                        InlineKeyboardButton.builder()
                                .text(traps.get(1).buttonLabel())
                                .callbackData("forest_trap:1")
                                .build()))
                .keyboardRow(new InlineKeyboardRow(
                        InlineKeyboardButton.builder()
                                .text(traps.get(2).buttonLabel())
                                .callbackData("forest_trap:2")
                                .build()))
                .build();

        return new BotResponse(text, null, kb, null, null, "HTML");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static int totalStored(Island island) {
        return island.getWood() + island.getStone() + island.getFish()
                + island.getShells() + island.getCoral()
                + island.getMeat() + island.getFur();
    }

    // ── Records ───────────────────────────────────────────────────────────────

    public record PatrolResult(int meat, int fur, boolean rare, String flavorText) {}
}
