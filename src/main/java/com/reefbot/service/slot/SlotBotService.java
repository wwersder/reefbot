package com.reefbot.service.slot;

import com.reefbot.dto.BotResponse;
import com.reefbot.entity.Island;
import com.reefbot.entity.Player;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;
import org.telegram.telegrambots.meta.api.objects.webapp.WebAppInfo;

import java.util.List;

/**
 * Handles /slot command in the bot private chat.
 */
@Service
public class SlotBotService {

    @Value("${reefbot.slot.mini-app-url:https://reefbot.online/mini/slot/}")
    private String miniAppUrl;

    public BotResponse handle(Player player) {
        Island island = player.getIsland();
        int balance   = island != null ? island.getShells() : 0;

        String text = String.format("""
                🎰 <b>The Reef House</b>

                5 барабанов, 9 линий, бонусный раунд с растущим множителем.
                Три 🏺 — и тебя ждут бесплатные спины!

                Баланс: 🐚 %d""", balance);

        String freshUrl = miniAppUrl + "?t=" + (System.currentTimeMillis() / 60_000);

        InlineKeyboardMarkup keyboard = InlineKeyboardMarkup.builder()
                .keyboardRow(new InlineKeyboardRow(List.of(
                        InlineKeyboardButton.builder()
                                .text("🎰 Играть")
                                .webApp(new WebAppInfo(freshUrl))
                                .build()
                )))
                .build();

        return BotResponse.html(text, keyboard);
    }
}
