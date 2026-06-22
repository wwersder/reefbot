package com.reefbot.service.plinko;

import com.reefbot.dto.BotResponse;
import com.reefbot.entity.Island;
import com.reefbot.entity.Player;
import com.reefbot.entity.PlinkoLog;
import com.reefbot.repository.PlinkoLogRepository;
import com.reefbot.util.Fmt;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;
import org.telegram.telegrambots.meta.api.objects.webapp.WebAppInfo;

import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Handles /plinko and /plinko top commands in the bot private chat.
 */
@Service
@RequiredArgsConstructor
public class PlinkoBotService {

    @Value("${reefbot.plinko.mini-app-url:https://reefbot.online/mini/plinko/}")
    private String miniAppUrl;

    private final PlinkoLogRepository plinkoLogRepository;

    @Transactional(readOnly = true)
    public BotResponse handle(Player player, String text) {
        // /plinko top — leaderboard in bot chat
        if (text.startsWith("/plinko top")) {
            return handleTop();
        }
        // /plinko — launch Mini App
        return handleLaunch(player);
    }

    // ── /plinko ───────────────────────────────────────────────────────────────

    private BotResponse handleLaunch(Player player) {
        Island island  = player.getIsland();
        int balance    = island != null ? island.getShells() : 0;

        String text = String.format("""
                🪷 <b>Reef Plinko</b>

                Брось жемчужину сквозь коралловые кольца и испытай удачу!
                Выигрыши — в ракушках твоего острова.

                Баланс: 🐚 %s""", Fmt.n(balance));

        // Append a timestamp so Telegram WebView never serves a cached version
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

    // ── /plinko top ───────────────────────────────────────────────────────────

    private BotResponse handleTop() {
        List<Object[]> topWin  = plinkoLogRepository.findTopByProfitWithPlayer();
        List<Object[]> topMult = plinkoLogRepository.findTopByMultiplierWithPlayer();

        if (topWin.isEmpty()) {
            return BotResponse.html("🏆 <b>Таблица рекордов Plinko</b>\n\nПока никто не играл. Стань первым! 🪷");
        }

        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("dd.MM.yy");
        StringBuilder sb = new StringBuilder("🏆 <b>Reef Plinko — Рекорды</b>\n\n");

        sb.append("💰 <b>Лучший выигрыш</b>\n");
        int rank = 1;
        for (Object[] row : topWin.subList(0, Math.min(5, topWin.size()))) {
            PlinkoLog l = (PlinkoLog) row[0];
            Player    p = (Player)    row[1];
            String name = p.getUsername() != null ? "@" + p.getUsername() : "Игрок #" + p.getId();
            int profit  = l.getWon() - l.getBet();
            sb.append(String.format("%d. %s — <b>+%s 🐚</b> (×%.0f) %s\n",
                    rank++, name, Fmt.n(profit), l.getMultiplier(), l.getPlayedAt().format(fmt)));
        }

        sb.append("\n🎯 <b>Лучший множитель</b>\n");
        rank = 1;
        for (Object[] row : topMult.subList(0, Math.min(5, topMult.size()))) {
            PlinkoLog l = (PlinkoLog) row[0];
            Player    p = (Player)    row[1];
            String name = p.getUsername() != null ? "@" + p.getUsername() : "Игрок #" + p.getId();
            sb.append(String.format("%d. %s — <b>×%.0f</b> (+%s 🐚) %s\n",
                    rank++, name, l.getMultiplier(), Fmt.n(l.getWon() - l.getBet()),
                    l.getPlayedAt().format(fmt)));
        }

        return BotResponse.html(sb.toString());
    }
}
