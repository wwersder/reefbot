package com.reefbot.service.slotwar;

import com.reefbot.dto.BotResponse;
import com.reefbot.entity.Island;
import com.reefbot.entity.Player;
import com.reefbot.util.Fmt;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;
import org.telegram.telegrambots.meta.api.objects.webapp.WebAppInfo;

import java.util.List;

/**
 * Handles /slotwar command in the bot private chat.
 */
@Service
public class SlotWarBotService {

    @Value("${reefbot.slotwar.mini-app-url:https://reefbot.online/mini/slot-war/}")
    private String miniAppUrl;

    public BotResponse handle(Player player) {
        Island island = player.getIsland();
        int balance   = island != null ? island.getShells() : 0;

        String text = String.format("""
                ⚔️ <b>Шторм vs Штиль</b>

                5×5 барабанов · 15 линий · множители до ×250

                Выбери стихию:
                ☀️ <b>Штиль</b> — чаще побеждаешь, но меньше
                ⚡ <b>Шторм</b> — редко, но громко

                🌊 Вайлд расширяется на весь барабан и тянет множитель.
                Три маяка 🗼 — 10 бесплатных спинов с липкими вайлдами!

                Баланс: 🐚 %s""", Fmt.n(balance));

        String freshUrl = miniAppUrl + "?t=" + (System.currentTimeMillis() / 60_000);

        InlineKeyboardMarkup keyboard = InlineKeyboardMarkup.builder()
                .keyboardRow(new InlineKeyboardRow(List.of(
                        InlineKeyboardButton.builder()
                                .text("⚔️ Играть")
                                .webApp(new WebAppInfo(freshUrl))
                                .build()
                )))
                .build();

        return BotResponse.html(text, keyboard);
    }
}
