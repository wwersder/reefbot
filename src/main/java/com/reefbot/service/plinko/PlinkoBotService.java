package com.reefbot.service.plinko;

import com.reefbot.dto.BotResponse;
import com.reefbot.entity.Island;
import com.reefbot.entity.Player;
import com.reefbot.entity.PlinkoLog;
import com.reefbot.repository.PlayerRepository;
import com.reefbot.repository.PlinkoLogRepository;
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
    private final PlayerRepository    playerRepository;

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

                Баланс: 🐚 %d""", balance);

        InlineKeyboardMarkup keyboard = InlineKeyboardMarkup.builder()
                .keyboardRow(new InlineKeyboardRow(List.of(
                        InlineKeyboardButton.builder()
                                .text("🎰 Играть")
                                .webApp(new WebAppInfo(miniAppUrl))
                                .build()
                )))
                .build();

        return BotResponse.html(text, keyboard);
    }

    // ── /plinko top ───────────────────────────────────────────────────────────

    private BotResponse handleTop() {
        List<PlinkoLog> topWin  = plinkoLogRepository.findTopByProfit();
        List<PlinkoLog> topMult = plinkoLogRepository.findTopByMultiplier();

        if (topWin.isEmpty()) {
            return BotResponse.html("🏆 <b>Таблица рекордов Plinko</b>\n\nПока никто не играл. Стань первым! 🪷");
        }

        StringBuilder sb = new StringBuilder("🏆 <b>Reef Plinko — Рекорды</b>\n\n");

        sb.append("💰 <b>Лучший выигрыш</b>\n");
        int rank = 1;
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("dd.MM.yy");
        for (PlinkoLog l : topWin.subList(0, Math.min(5, topWin.size()))) {
            String name = resolveUsername(l.getPlayerId());
            int profit  = l.getWon() - l.getBet();
            sb.append(String.format("%d. %s — <b>+%d 🐚</b> (×%.0f) %s\n",
                    rank++, name, profit, l.getMultiplier(), l.getPlayedAt().format(fmt)));
        }

        sb.append("\n🎯 <b>Лучший множитель</b>\n");
        rank = 1;
        for (PlinkoLog l : topMult.subList(0, Math.min(5, topMult.size()))) {
            String name = resolveUsername(l.getPlayerId());
            sb.append(String.format("%d. %s — <b>×%.0f</b> (+%d 🐚) %s\n",
                    rank++, name, l.getMultiplier(), l.getWon() - l.getBet(),
                    l.getPlayedAt().format(fmt)));
        }

        return BotResponse.html(sb.toString());
    }

    private String resolveUsername(Long playerId) {
        return playerRepository.findById(playerId)
                .map(p -> p.getUsername() != null ? "@" + p.getUsername() : "Игрок #" + p.getId())
                .orElse("?");
    }
}
