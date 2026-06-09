package com.reefbot.bot.handlers;

import com.reefbot.bot.CallbackHandler;
import com.reefbot.dto.BotResponse;
import com.reefbot.entity.Player;
import com.reefbot.service.game.FishingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;

@Slf4j
@Component
@RequiredArgsConstructor
public class LevelsCallbackHandler implements CallbackHandler {

    private static final int MAX_LEVEL = 10;
    // Cumulative XP thresholds (must match FishingService)
    private static final int[] XP_THRESHOLDS = {0, 50, 150, 350, 700, 1200, 2000, 3500, 6000, 10000};

    private final TelegramClient telegramClient;

    @Override
    public String getPrefix() {
        return "levels";
    }

    /** Called by GameService when player types /levels — sends first page. */
    public static BotResponse buildInitialMessage(Player player) {
        int startLevel = player != null ? player.getFishing().getFishingLevel() : 1;
        int playerLevel = player != null ? player.getFishing().getFishingLevel() : 0;
        int playerXp    = player != null ? player.getFishing().getFishingXp()    : 0;

        String text = buildPageTextStatic(startLevel, playerLevel, playerXp);
        InlineKeyboardMarkup keyboard = buildKeyboardStatic(startLevel);
        return new BotResponse(text, null, keyboard, null, null, "HTML");
    }

    @Override
    public void handle(String payload, long chatId, int messageId, Player player) {
        int page;
        try {
            page = Integer.parseInt(payload);
        } catch (NumberFormatException e) {
            page = 1;
        }
        page = Math.max(1, Math.min(page, MAX_LEVEL));

        int playerLevel = player != null ? player.getFishing().getFishingLevel() : 0;
        int playerXp    = player != null ? player.getFishing().getFishingXp()    : 0;

        String text   = buildPageText(page, playerLevel, playerXp);
        InlineKeyboardMarkup keyboard = buildKeyboard(page);

        try {
            telegramClient.execute(EditMessageText.builder()
                    .chatId(chatId)
                    .messageId(messageId)
                    .text(text)
                    .parseMode("HTML")
                    .replyMarkup(keyboard)
                    .build());
        } catch (TelegramApiException e) {
            log.error("Failed to edit levels message", e);
        }
    }

    private static String buildPageTextStatic(int level, int playerLevel, int playerXp) {
        return buildPageTextImpl(level, playerLevel, playerXp);
    }

    private String buildPageText(int level, int playerLevel, int playerXp) {
        return buildPageTextImpl(level, playerLevel, playerXp);
    }

    private static String buildPageTextImpl(int level, int playerLevel, int playerXp) {
        String name = FishingService.levelName(level);
        String unlockText = FishingService.levelUnlockText(level);
        int xpRequired = XP_THRESHOLDS[level - 1];
        int xpNext = level < MAX_LEVEL ? XP_THRESHOLDS[level] : -1;

        StringBuilder sb = new StringBuilder();

        // Header with current indicator
        if (level == playerLevel) {
            sb.append("📍 <b>").append(name).append("</b> · Уровень ").append(level);
            sb.append(" ← вы здесь");
        } else if (level < playerLevel) {
            sb.append("✅ <b>").append(name).append("</b> · Уровень ").append(level);
        } else {
            sb.append("🔒 <b>").append(name).append("</b> · Уровень ").append(level);
        }

        sb.append("\n\n");

        // XP info
        sb.append("⭐ Нужно XP: <b>").append(xpRequired).append("</b>");
        if (xpNext >= 0) {
            sb.append("  →  следующий: <b>").append(xpNext).append("</b>");
        } else {
            sb.append("  (максимальный уровень)");
        }

        // Progress for current level
        if (level == playerLevel && xpNext >= 0) {
            int remaining = xpNext - playerXp;
            sb.append("\n📊 Ваш XP: ").append(playerXp).append(" / ").append(xpNext);
            sb.append("  (осталось: ").append(remaining).append(")");
        } else if (level > playerLevel) {
            int remaining = xpRequired - playerXp;
            if (remaining > 0) {
                sb.append("\n📊 До этого уровня: ещё <b>").append(remaining).append(" XP</b>");
            }
        }

        // Bonuses
        sb.append("\n\n");
        if (unlockText != null) {
            sb.append("🎁 <b>Бонус уровня:</b>\n").append(unlockText);
        } else {
            sb.append("🎁 <b>Бонус:</b> недоступен на этом уровне");
        }

        // Footer
        sb.append("\n\n<i>").append(level).append(" / ").append(MAX_LEVEL).append("</i>");

        return sb.toString();
    }

    private static InlineKeyboardMarkup buildKeyboardStatic(int current) {
        return buildKeyboardImpl(current);
    }

    private InlineKeyboardMarkup buildKeyboard(int current) {
        return buildKeyboardImpl(current);
    }

    private static InlineKeyboardMarkup buildKeyboardImpl(int current) {
        InlineKeyboardRow row = new InlineKeyboardRow();

        if (current > 1) {
            row.add(InlineKeyboardButton.builder()
                    .text("◀️")
                    .callbackData("levels:" + (current - 1))
                    .build());
        }

        if (current < MAX_LEVEL) {
            row.add(InlineKeyboardButton.builder()
                    .text("▶️")
                    .callbackData("levels:" + (current + 1))
                    .build());
        }

        return InlineKeyboardMarkup.builder().keyboardRow(row).build();
    }
}
