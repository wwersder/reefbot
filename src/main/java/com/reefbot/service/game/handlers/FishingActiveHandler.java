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

    private final FishingService fishingService;
    private final PlayerRepository playerRepository;

    @Override
    public PlayerScreen getScreen() {
        return PlayerScreen.FISHING_ACTIVE;
    }

    @Override
    public BotResponse handle(Player player, Island island, String text) {
        if (FishingMenuHandler.BTN_BACK.equals(text)) {
            player.setCurrentScreen(PlayerScreen.MAIN);
            playerRepository.save(player);
            return MainMenuHandler.showMainMenu(player, island);
        }

        if (fishingService.isReady(player)) {
            player.setCurrentScreen(PlayerScreen.FISHING_RESULT);
            playerRepository.save(player);
            return FishingResultHandler.buildResultScreen(player, island, fishingService);
        }

        return buildStatusScreen(player, fishingService, backKeyboard());
    }

    public static BotResponse buildStatusScreen(Player player, FishingService fishingService, ReplyKeyboard keyboard) {
        long remaining = fishingService.minutesRemaining(player);
        String spot = player.getFishingSpot() != null
                ? player.getFishingSpot().getDisplayName()
                : "неизвестно";

        String text = String.format("""
                ⏳ Удочка заброшена %s

                Осталось: %d мин
                """, spot.toLowerCase(), remaining);

        return new BotResponse(text, null, keyboard);
    }

    private static ReplyKeyboard backKeyboard() {
        return KeyboardBuilder.builder().row(FishingMenuHandler.BTN_BACK).build();
    }
}
