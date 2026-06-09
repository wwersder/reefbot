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

    public static final String BTN_REFRESH = "🔄 Обновить";

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

        // BTN_REFRESH or any other input — re-show status with fresh time
        return buildStatusScreen(player, fishingService, activeKeyboard());
    }

    public static BotResponse buildStatusScreen(Player player, FishingService fishingService, ReplyKeyboard keyboard) {
        String remaining = fishingService.timeRemainingText(player);
        String spot = player.getFishingSpot() != null
                ? player.getFishingSpot().getDisplayName().toLowerCase()
                : "неизвестно";

        String text = String.format("""
                ⏳ Удочка заброшена %s

                Возвращайся через %s — улов будет ждать.
                """, spot, remaining);

        return new BotResponse(text, null, keyboard);
    }

    public static ReplyKeyboard activeKeyboard() {
        return KeyboardBuilder.builder()
                .row(BTN_REFRESH, FishingMenuHandler.BTN_BACK)
                .build();
    }
}
