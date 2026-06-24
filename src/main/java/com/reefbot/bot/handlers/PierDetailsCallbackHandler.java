package com.reefbot.bot.handlers;

import com.reefbot.bot.CallbackHandler;
import com.reefbot.dto.BotResponse;
import com.reefbot.entity.Island;
import com.reefbot.entity.IslandBuilding;
import com.reefbot.entity.Player;
import com.reefbot.enums.BuildingType;
import com.reefbot.service.game.BuildingService;
import com.reefbot.service.game.handlers.ShorePierHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.util.Optional;

/**
 * Inline-keyboard details panel for the Fishing Pier building.
 * Callback data format: "pier:{level}" — browse upgrade levels with ◀️/▶️.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PierDetailsCallbackHandler implements CallbackHandler {

    private final TelegramClient telegramClient;
    private final BuildingService buildingService;

    @Override
    public String getPrefix() {
        return "pier";
    }

    /**
     * Builds the initial details message shown when player taps 📋 Детали.
     *
     * @param viewLevel    the level page to show (usually current pier level)
     * @param currentLevel actual pier level of the player (for indicators)
     */
    public static BotResponse buildDetailsMessage(int viewLevel, int currentLevel) {
        String text = buildText(viewLevel, currentLevel);
        InlineKeyboardMarkup keyboard = buildKeyboard(viewLevel);
        return new BotResponse(text, null, keyboard, null, null, "HTML");
    }

    @Override
    public void handle(String payload, long chatId, int messageId, Player player) {
        int viewLevel;
        try {
            viewLevel = Math.max(1, Integer.parseInt(payload));
        } catch (NumberFormatException e) {
            viewLevel = 1;
        }

        int currentLevel = 0;
        Island island = player.getIsland();
        if (island != null) {
            Optional<IslandBuilding> pierOpt = buildingService.find(island, BuildingType.FISHING_PIER);
            currentLevel = pierOpt.map(IslandBuilding::getLevel).orElse(0);
        }

        String text = buildText(viewLevel, currentLevel);
        InlineKeyboardMarkup keyboard = buildKeyboard(viewLevel);

        try {
            telegramClient.execute(EditMessageText.builder()
                    .chatId(chatId)
                    .messageId(messageId)
                    .text(text)
                    .parseMode("HTML")
                    .replyMarkup(keyboard)
                    .build());
        } catch (TelegramApiException e) {
            log.error("Failed to edit pier details message", e);
        }
    }

    // ── Text builder ───────────────────────────────────────────────────────

    private static String buildText(int viewLevel, int currentLevel) {
        BuildingType bt = BuildingType.FISHING_PIER;
        String name = bt.nameAt(viewLevel);
        int prod = bt.productionPerHourAt(viewLevel);
        int cap  = bt.capAt(viewLevel);

        String indicator;
        if (currentLevel == 0) {
            indicator = "🔒 не построен";
        } else if (viewLevel == currentLevel) {
            indicator = "📍 текущий уровень";
        } else if (viewLevel < currentLevel) {
            indicator = "✅ пройден";
        } else {
            indicator = "🔒 ещё не достигнут";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("📋 <b>Детали помоста</b>\n\n");
        sb.append(indicator).append("\n");
        sb.append("<b>Уровень ").append(viewLevel).append("</b> · ").append(name).append("\n");
        sb.append("Производство: <b>+").append(prod).append(" 🐟/ч</b>");
        sb.append("  ·  Потолок: <b>").append(cap).append(" 🐟</b>\n");

        // Upgrade cost to next level
        int nextLvl = viewLevel + 1;
        sb.append("\n⬆️ <b>Улучшение до ур. ").append(nextLvl).append(":</b>\n");
        sb.append(costsLine(bt, nextLvl));
        sb.append("  ·  ⏱ ").append(ShorePierHandler.minutesToText(bt.buildMinutesFor(nextLvl)));

        sb.append("\n\n<i>ур. ").append(viewLevel).append("</i>");
        return sb.toString();
    }

    private static String costsLine(BuildingType bt, int level) {
        StringBuilder sb = new StringBuilder();
        sb.append(bt.fishCostFor(level)).append(" 🐟");
        if (bt.shellsCostFor(level) > 0) sb.append("  ").append(bt.shellsCostFor(level)).append(" 🐚");
        if (bt.woodCostFor(level)   > 0) sb.append("  ").append(bt.woodCostFor(level)).append(" 🪵");
        return sb.toString();
    }

    // ── Keyboard builder ───────────────────────────────────────────────────

    private static InlineKeyboardMarkup buildKeyboard(int viewLevel) {
        InlineKeyboardRow row = new InlineKeyboardRow();
        int prev = Math.max(1, viewLevel - 1);
        int next = viewLevel + 1;

        row.add(InlineKeyboardButton.builder()
                .text("◀️")
                .callbackData("pier:" + prev)
                .build());
        row.add(InlineKeyboardButton.builder()
                .text("▶️")
                .callbackData("pier:" + next)
                .build());

        return InlineKeyboardMarkup.builder().keyboardRow(row).build();
    }
}
