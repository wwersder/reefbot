package com.reefbot.service;

import com.reefbot.dto.BotResponse;
import com.reefbot.entity.Player;
import com.reefbot.service.game.TideService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
@RequiredArgsConstructor
public class AdminService {

    private final PlayerService playerService;
    private final TideService tideService;

    public BotResponse handle(String text) {

        // /del <playerID>
        if (text.startsWith("/del ")) {
            try {
                long playerId = Long.parseLong(text.substring(5).trim());
                boolean deleted = playerService.deletePlayer(playerId);
                return new BotResponse(deleted
                        ? "Игрок #" + playerId + " удалён."
                        : "Игрок #" + playerId + " не найден.");
            } catch (NumberFormatException e) {
                return new BotResponse("Неверный формат. Используй: /del <playerID>");
            }
        }

        // /tide <playerID>
        if (text.startsWith("/tide ")) {
            try {
                long playerId = Long.parseLong(text.substring(6).trim());
                Optional<Player> playerOpt = playerService.findById(playerId);
                if (playerOpt.isEmpty()) {
                    return new BotResponse("Игрок #" + playerId + " не найден.");
                }
                Player player = playerOpt.get();
                tideService.forceTide(player);
                return new BotResponse("🌊 Прилив активирован для игрока #" + playerId + ".");
            } catch (NumberFormatException e) {
                return new BotResponse("Неверный формат. Используй: /tide <playerID>");
            }
        }

        return null;
    }
}
