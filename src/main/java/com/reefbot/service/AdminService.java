package com.reefbot.service;

import com.reefbot.dto.BotResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AdminService {

    private final PlayerService playerService;

    public BotResponse handle(String text) {
        if (text.startsWith("/del ")) {
            try {
                long playerId = Long.parseLong(text.substring(5).trim());
                boolean deleted = playerService.deletePlayer(playerId);
                String reply = deleted
                        ? "Игрок #" + playerId + " удалён."
                        : "Игрок #" + playerId + " не найден.";
                return new BotResponse(reply);
            } catch (NumberFormatException e) {
                return new BotResponse("Неверный формат. Используй: /del <playerID>");
            }
        }
        return null;
    }
}
