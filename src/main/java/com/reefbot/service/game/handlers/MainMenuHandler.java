package com.reefbot.service.game.handlers;

import com.reefbot.dto.BotResponse;
import com.reefbot.entity.Player;
import com.reefbot.enums.PlayerScreen;
import com.reefbot.service.PlayerService;
import com.reefbot.util.KeyboardBuilder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup;

@Component
@RequiredArgsConstructor
public class MainMenuHandler {

    private final PlayerService playerService;

    public static final String BTN_ISLAND      = "🏝 Мой остров";
    public static final String BTN_BUILD       = "🏗 Строить";
    public static final String BTN_RESOURCES   = "📦 Ресурсы";
    public static final String BTN_EXPEDITIONS = "⚓ Экспедиции";
    public static final String BTN_BACK        = "← Назад";

    private static final String TEXT = """
            🏝 Главное меню

            Управляй своим островом:

            🏝 Мой остров — состояние и ресурсы
            🏗 Строить — возводить постройки
            📦 Ресурсы — текущие запасы
            ⚓ Экспедиции — скоро...""";

    public BotResponse handle(Player player) {
        player.setScreen(PlayerScreen.MAIN);
        player.setPendingAction(null);
        playerService.save(player);
        return new BotResponse(TEXT, null, buildKeyboard());
    }

    public static ReplyKeyboardMarkup buildKeyboard() {
        return KeyboardBuilder.builder()
                .row(BTN_ISLAND, BTN_BUILD)
                .row(BTN_RESOURCES, BTN_EXPEDITIONS)
                .build();
    }
}
