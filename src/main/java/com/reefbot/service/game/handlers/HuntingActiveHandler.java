package com.reefbot.service.game.handlers;

import com.reefbot.dto.BotResponse;
import com.reefbot.entity.Island;
import com.reefbot.entity.Player;
import com.reefbot.enums.HuntingSpot;
import com.reefbot.enums.PlayerScreen;
import com.reefbot.repository.PlayerRepository;
import com.reefbot.service.game.ForestEventService;
import com.reefbot.service.game.ForestEventService.PatrolResult;
import com.reefbot.service.game.GameHandler;
import com.reefbot.service.game.HuntingService;
import com.reefbot.util.KeyboardBuilder;
import com.reefbot.util.RichText;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboard;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardButton;

/**
 * Screen: active hunt wait screen (ZONE_FOREST_HUNT_ACTIVE).
 * Shows time remaining, beast sighting (if active), and patrol action.
 * Mirrors the beach screen pattern (ShoreBeachHandler): same layout with a periodic
 * event button (sighting = tide) and a cooldown action (patrol = beach scan).
 */
@Component
@RequiredArgsConstructor
public class HuntingActiveHandler implements GameHandler {

    public static final String BTN_SIGHTING = "🦌 Зверь рядом!";
    public static final String BTN_PATROL   = "🌿 Осмотреться";
    public static final String BTN_REFRESH  = "🔄 Обновить";
    public static final String BTN_BACK     = "◀️ В лес";

    private final HuntingService     huntingService;
    private final ForestEventService forestEventService;
    private final PlayerRepository   playerRepository;

    @Override
    public PlayerScreen getScreen() {
        return PlayerScreen.ZONE_FOREST_HUNT_ACTIVE;
    }

    @Override
    public BotResponse handle(Player player, Island island, String text) {
        if (BTN_BACK.equals(text)) {
            player.getState().setCurrentScreen(PlayerScreen.ZONE_FOREST);
            playerRepository.save(player);
            return ForestZoneHandler.buildZoneScreen(player, huntingService);
        }

        // Hunt just finished — redirect to collect screen
        if (huntingService.isReady(player)) {
            player.getState().setCurrentScreen(PlayerScreen.ZONE_FOREST_HUNT_RESULT);
            playerRepository.save(player);
            return HuntingResultHandler.buildResultScreen(player);
        }

        if (BTN_SIGHTING.equals(text)) {
            return claimSighting(player);
        }

        if (BTN_PATROL.equals(text)) {
            return doPatrol(player, island);
        }

        // BTN_REFRESH or default — refresh the status screen
        return buildStatusScreen(player, huntingService, forestEventService);
    }

    // ── Actions ───────────────────────────────────────────────────────────────

    private BotResponse claimSighting(Player player) {
        if (!forestEventService.isSightingActive(player)) {
            return buildStatusScreen(player, huntingService, forestEventService);
        }
        // Mark as interacted so the button disappears from the hunt screen
        player.getForest().setSightingClaimed(true);
        playerRepository.save(player);

        // Show inline trap-placement choices; result rolls at collect time
        com.reefbot.enums.HuntingSpot spot = player.getForest().getHuntingSpot();
        return forestEventService.buildTrapChoiceScreen(
                spot != null ? spot : com.reefbot.enums.HuntingSpot.EDGE);
    }

    private BotResponse doPatrol(Player player, Island island) {
        if (!forestEventService.isPatrolReady(player)) {
            return buildStatusScreen(player, huntingService, forestEventService);
        }

        PatrolResult result = forestEventService.scanPatrol(player, island);

        RichText rt = new RichText();
        rt.beginBold().add("🌿 Осмотрелся").endBold()
          .add("\n\n")
          .add(result.flavorText())
          .add("\n\n");

        if (result.rare()) {
            rt.beginBold()
              .add("+").add(String.valueOf(result.meat())).add(" 🥩")
              .endBold();
            if (result.fur() > 0) {
                rt.add("  ").beginBold().add("+").add(String.valueOf(result.fur())).add(" 🪶").endBold();
            }
            rt.add(" — редкая находка!");
        } else {
            rt.add("+").beginBold().add(String.valueOf(result.meat())).add(" 🥩").endBold();
            if (result.fur() > 0) {
                rt.add("  +").beginBold().add(String.valueOf(result.fur())).add(" 🪶").endBold();
            }
        }

        rt.add("\n\nСледующий осмотр ").add(forestEventService.patrolCooldownText(player)).add(".");

        return rt.build().withFollowUp(buildStatusScreen(player, huntingService, forestEventService));
    }

    // ── Static builders ───────────────────────────────────────────────────────

    /**
     * Builds the hunt wait screen showing time remaining, sighting status, and patrol status.
     * Mirrors {@code ShoreBeachHandler.buildBeachScreen()} in structure.
     */
    public static BotResponse buildStatusScreen(Player player,
                                                HuntingService huntingService,
                                                ForestEventService forestEventService) {
        HuntingSpot spot      = player.getForest().getHuntingSpot();
        String      remaining = huntingService.timeRemainingText(player);

        RichText rt = new RichText();
        rt.beginBold()
          .add("⏳ Охота · " + (spot != null ? spot.getDisplayName() : "Лес"))
          .endBold()
          .add("\n\n")
          .add("Возвращайся через ")
          .beginBold().add(remaining).endBold()
          .add(" — добыча будет ждать.\n\n");

        // Beast sighting status
        rt.add("🦌 ").bold("Зверь:").add(" ");
        if (forestEventService.isSightingActive(player)) {
            rt.add("рядом! Не упусти момент.");
        } else {
            rt.add("тихо");
        }
        rt.add("\n");

        // Patrol status
        rt.add("🌿 ").bold("Осмотр:").add(" ");
        if (forestEventService.isPatrolReady(player)) {
            rt.beginBold().add("можно осмотреться!").endBold();
        } else {
            rt.add(forestEventService.patrolCooldownText(player));
        }

        return rt.build(buildActiveKeyboard(player, forestEventService));
    }

    private static ReplyKeyboard buildActiveKeyboard(Player player, ForestEventService forestEventService) {
        KeyboardBuilder kb = KeyboardBuilder.builder();

        // Beast sighting — green button, only when window is open
        if (forestEventService.isSightingActive(player)) {
            KeyboardButton sightingBtn = new KeyboardButton(BTN_SIGHTING);
            sightingBtn.setStyle("success");
            kb.row(sightingBtn);
        }

        // Patrol — always visible; green when ready
        KeyboardButton patrolBtn = new KeyboardButton(BTN_PATROL);
        if (forestEventService.isPatrolReady(player)) patrolBtn.setStyle("success");
        kb.row(patrolBtn);

        kb.row(BTN_REFRESH, BTN_BACK);
        return kb.build();
    }

    /**
     * Minimal keyboard without event buttons — used when ForestEventService is not available
     * (e.g., hunt-start confirmation message in HuntingMenuHandler).
     */
    public static ReplyKeyboard activeKeyboard() {
        return KeyboardBuilder.builder()
                .row(BTN_REFRESH, BTN_BACK)
                .build();
    }
}
