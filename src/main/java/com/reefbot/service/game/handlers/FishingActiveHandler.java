package com.reefbot.service.game.handlers;

import com.reefbot.dto.BotResponse;
import com.reefbot.entity.Island;
import com.reefbot.entity.Player;
import com.reefbot.enums.PlayerScreen;
import com.reefbot.repository.PlayerRepository;
import com.reefbot.service.game.FishingService;
import com.reefbot.service.game.GameHandler;
import com.reefbot.util.KeyboardBuilder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboard;

@Component
@RequiredArgsConstructor
public class FishingActiveHandler implements GameHandler {

    public static final String BTN_REFRESH   = "🔄 Обновить";
    public static final String BTN_INVENTORY = FishingMenuHandler.BTN_INVENTORY;

    private final FishingService fishingService;
    private final FishingInventoryHandler fishingInventoryHandler;
    private final PlayerRepository playerRepository;
    private final com.reefbot.service.game.TideService tideService;

    @Override
    public PlayerScreen getScreen() {
        return PlayerScreen.FISHING_ACTIVE;
    }

    @Override
    public BotResponse handle(Player player, Island island, String text) {
        if (FishingMenuHandler.BTN_BACK.equals(text)) {
            player.getState().setCurrentScreen(PlayerScreen.ZONE_SHORE);
            playerRepository.save(player);
            return ShoreZoneHandler.buildZoneScreen(player, tideService);
        }

        if (BTN_INVENTORY.equals(text)) {
            player.getState().setCurrentScreen(PlayerScreen.FISHING_INVENTORY);
            playerRepository.save(player);
            return fishingInventoryHandler.buildScreen(player);
        }

        if (fishingService.isReady(player)) {
            player.getState().setCurrentScreen(PlayerScreen.FISHING_RESULT);
            playerRepository.save(player);
            return FishingResultHandler.buildResultScreen(player, island, fishingService);
        }

        // BTN_REFRESH or any other input — re-show status with fresh time
        return buildStatusScreen(player, fishingService, activeKeyboard());
    }

    public static BotResponse buildStatusScreen(Player player, FishingService fishingService, ReplyKeyboard keyboard) {
        String remaining = fishingService.timeRemainingText(player);
        String spot = player.getFishing().getFishingSpot() != null
                ? player.getFishing().getFishingSpot().getDisplayName().toLowerCase()
                : "неизвестно";

        String effectsLine = FishingMenuHandler.activeEffectsLine(player);
        String effectsText = effectsLine.isEmpty() ? "" : "\n⚡ Активно: " + effectsLine;

        String text = String.format("""
                ⏳ Удочка заброшена %s%s

                Возвращайся через %s — улов будет ждать.
                """, spot, effectsText, remaining);

        return new BotResponse(text, null, keyboard);
    }

    public static ReplyKeyboard activeKeyboard() {
        return KeyboardBuilder.builder()
                .row(BTN_REFRESH, BTN_INVENTORY, FishingMenuHandler.BTN_BACK)
                .build();
    }
}
