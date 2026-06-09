package com.reefbot.service.game;

import com.reefbot.dto.BotResponse;
import com.reefbot.entity.Island;
import com.reefbot.entity.Player;
import com.reefbot.enums.PlayerScreen;

public interface GameHandler {

    PlayerScreen getScreen();

    BotResponse handle(Player player, Island island, String text);
}
