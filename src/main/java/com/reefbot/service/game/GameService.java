package com.reefbot.service.game;

import com.reefbot.bot.handlers.LevelsCallbackHandler;
import com.reefbot.dto.BotResponse;
import com.reefbot.entity.Island;
import com.reefbot.entity.Player;
import com.reefbot.enums.PlayerScreen;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class GameService {

    private final List<GameHandler> handlers;

    private Map<PlayerScreen, GameHandler> handlerMap;

    @PostConstruct
    void init() {
        handlerMap = handlers.stream()
                .collect(Collectors.toMap(
                        GameHandler::getScreen,
                        Function.identity(),
                        (a, b) -> { throw new IllegalStateException("Duplicate handler: " + a.getScreen()); },
                        () -> new EnumMap<>(PlayerScreen.class)
                ));
    }

    public BotResponse handle(Player player, String text) {
        // Global commands available from any screen
        if ("/levels".equals(text)) {
            return LevelsCallbackHandler.buildInitialMessage(player);
        }

        Island island = player.getIsland();
        PlayerScreen screen = player.getState() != null && player.getState().getCurrentScreen() != null
                ? player.getState().getCurrentScreen()
                : PlayerScreen.MAIN;

        GameHandler handler = handlerMap.get(screen);
        if (handler == null) {
            // Fallback to MAIN if no handler registered for this screen
            handler = handlerMap.get(PlayerScreen.MAIN);
        }
        return handler.handle(player, island, text);
    }
}
