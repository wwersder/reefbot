package com.reefbot.service.game.handlers;

import com.reefbot.dto.BotResponse;
import com.reefbot.entity.Island;
import com.reefbot.entity.Player;
import com.reefbot.enums.HuntingSpot;
import com.reefbot.enums.PlayerScreen;
import com.reefbot.repository.PlayerRepository;
import com.reefbot.service.game.GameHandler;
import com.reefbot.service.game.HuntingService;
import com.reefbot.util.KeyboardBuilder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboard;

@Component
@RequiredArgsConstructor
public class HuntingActiveHandler implements GameHandler {

    public static final String BTN_REFRESH = "🔄 Обновить";
    public static final String BTN_BACK    = "◀️ В лес";

    private final HuntingService huntingService;
    private final PlayerRepository playerRepository;

    @Override
    public PlayerScreen getScreen() {
        return PlayerScreen.ZONE_FOREST_HUNT_ACTIVE;
    }

    @Override
    public BotResponse handle(Player player, Island island, String text) {
        // "В лес" — back to forest hub (no redirect loop now, hub shows inline status)
        if (BTN_BACK.equals(text)) {
            player.getState().setCurrentScreen(PlayerScreen.ZONE_FOREST);
            playerRepository.save(player);
            return ForestZoneHandler.buildZoneScreen(player, huntingService);
        }

        // Hunt finished — redirect to collect screen
        if (huntingService.isReady(player)) {
            player.getState().setCurrentScreen(PlayerScreen.ZONE_FOREST_HUNT_RESULT);
            playerRepository.save(player);
            return HuntingResultHandler.buildResultScreen(player);
        }

        // BTN_REFRESH or any other input — refresh the wait screen
        return buildStatusScreen(player, huntingService);
    }

    // ── Static builders (called from ForestZoneHandler.goActiveScreen) ────────

    public static BotResponse buildStatusScreen(Player player, HuntingService huntingService) {
        HuntingSpot spot = player.getForest().getHuntingSpot();
        String spotName  = spot != null ? spot.getDisplayName().toLowerCase() : "лес";
        String remaining = huntingService.timeRemainingText(player);

        String text = "⏳ Охота идёт " + spotName + "\n\n"
                + "Возвращайся через " + remaining + " — добыча будет ждать.";
        return new BotResponse(text, null, activeKeyboard());
    }

    public static ReplyKeyboard activeKeyboard() {
        return KeyboardBuilder.builder()
                .row(BTN_REFRESH, BTN_BACK)
                .build();
    }
}
