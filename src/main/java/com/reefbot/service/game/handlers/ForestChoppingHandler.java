package com.reefbot.service.game.handlers;

import com.reefbot.dto.BotResponse;
import com.reefbot.entity.Island;
import com.reefbot.entity.Player;
import com.reefbot.enums.ForestActivity;
import com.reefbot.enums.PlayerScreen;
import com.reefbot.repository.PlayerRepository;
import com.reefbot.service.game.ForestService;
import com.reefbot.service.game.GameHandler;
import com.reefbot.util.KeyboardBuilder;
import com.reefbot.util.RichText;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ForestChoppingHandler implements GameHandler {

    public static final String BTN_NEAR = "🌳 Поближе (~20 мин)";
    public static final String BTN_FAR  = "🌲 Подальше (~45 мин)";
    public static final String BTN_BACK = "◀️ Назад";

    private final ForestService forestService;
    private final PlayerRepository playerRepository;

    @Override
    public PlayerScreen getScreen() {
        return PlayerScreen.ZONE_FOREST_CHOPPING;
    }

    @Override
    public BotResponse handle(Player player, Island island, String text) {
        return switch (text) {
            case BTN_NEAR -> startAndReturn(player, ForestActivity.CHOP_NEAR);
            case BTN_FAR  -> startAndReturn(player, ForestActivity.CHOP_FAR);
            case BTN_BACK -> goBackToForest(player);
            default       -> buildChoppingMenu();
        };
    }

    private BotResponse startAndReturn(Player player, ForestActivity activity) {
        forestService.startActivity(player, activity);
        player.getState().setCurrentScreen(PlayerScreen.ZONE_FOREST);
        playerRepository.save(player);
        return ForestZoneHandler.buildZoneScreen(player, forestService);
    }

    private BotResponse goBackToForest(Player player) {
        player.getState().setCurrentScreen(PlayerScreen.ZONE_FOREST);
        playerRepository.save(player);
        return ForestZoneHandler.buildZoneScreen(player, forestService);
    }

    public static BotResponse buildChoppingMenu() {
        RichText rt = new RichText();
        rt.bold("🪓 Рубить деревья").add("\n\n")
          .add("Выбери маршрут:\n\n")
          .bold("🌳 Поближе").add(" — ~20 мин, 5–12 🪵\n")
          .bold("🌲 Подальше").add(" — ~45 мин, 12–28 🪵 + шанс 🐚");

        return rt.build(KeyboardBuilder.builder()
                .row(BTN_NEAR, BTN_FAR)
                .row(BTN_BACK)
                .build());
    }
}
