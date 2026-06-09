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
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardButton;

@Component
@RequiredArgsConstructor
public class MainMenuHandler implements GameHandler {

    public static final String BTN_ISLAND  = "🏝 Мой остров";
    public static final String BTN_BUILD   = "🏗 Строить";
    public static final String BTN_INV     = "📦 Инвентарь";
    public static final String BTN_FISHING = "🎣 Рыбалка";

    private final FishingService fishingService;
    private final PlayerRepository playerRepository;

    @Override
    public PlayerScreen getScreen() {
        return PlayerScreen.MAIN;
    }

    @Override
    public BotResponse handle(Player player, Island island, String text) {
        // Route button presses
        return switch (text) {
            case BTN_FISHING -> routeFishing(player, island);
            case BTN_ISLAND  -> new BotResponse("⚙️ Экран острова — в разработке.", null, keyboard(player));
            case BTN_BUILD   -> new BotResponse("⚙️ Строительство — скоро!", null, keyboard(player));
            case BTN_INV     -> new BotResponse("⚙️ Инвентарь — скоро!", null, keyboard(player));
            default          -> showMainMenu(player, island);
        };
    }

    /** Redirect fishing button based on current fishing state. */
    private BotResponse routeFishing(Player player, Island island) {
        if (fishingService.isReady(player)) {
            player.getState().setCurrentScreen(PlayerScreen.FISHING_RESULT);
            playerRepository.save(player);
            return FishingResultHandler.buildResultScreen(player, island, fishingService);
        }
        if (fishingService.isActive(player)) {
            player.getState().setCurrentScreen(PlayerScreen.FISHING_ACTIVE);
            playerRepository.save(player);
            return FishingActiveHandler.buildStatusScreen(player, fishingService,
                    FishingActiveHandler.activeKeyboard());
        }
        player.getState().setCurrentScreen(PlayerScreen.FISHING_MENU);
        playerRepository.save(player);
        return FishingMenuHandler.buildFishingMenu(player);
    }

    /** Main menu text + keyboard. */
    public static BotResponse showMainMenu(Player player, Island island) {
        String stage = stageFor(island.getDevPoints());
        String text = String.format("""
                🏝 Остров «%s»
                %s · %d ОР
                """, island.getName(), stage, island.getDevPoints());
        return new BotResponse(text, null, keyboard(player));
    }

    public static ReplyKeyboard keyboard(Player player) {
        return KeyboardBuilder.builder()
                .row(BTN_ISLAND, BTN_BUILD)
                .row(new KeyboardButton(BTN_INV), buildFishingButton(player))
                .build();
    }

    private static KeyboardButton buildFishingButton(Player player) {
        java.time.LocalDateTime finishAt = player.getFishing().getFishingFinishAt();
        KeyboardButton btn = new KeyboardButton(BTN_FISHING);
        // Green when catch is ready and not yet collected
        if (finishAt != null && !java.time.LocalDateTime.now().isBefore(finishAt)) {
            btn.setStyle("success");
        }
        return btn;
    }

    public static String stageFor(int devPoints) {
        if (devPoints >= 61) return "🏙 Процветающий город";
        if (devPoints >= 21) return "⚓ Развивающийся порт";
        if (devPoints >= 7)  return "🏘 Рыбацкая деревня";
        return "🌱 Дикий островок";
    }
}
