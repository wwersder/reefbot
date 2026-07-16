package com.reefbot.bot.handlers;

import com.reefbot.bot.CallbackHandler;
import com.reefbot.dto.BotResponse;
import com.reefbot.entity.Player;
import com.reefbot.service.game.HuntingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

/**
 * Inline-keyboard pager for hunter levels.
 * Mirrors {@link LevelsCallbackHandler} exactly — one page per level,
 * ◀️/▶️ arrows edit the same message in place.
 *
 * <p>Callback data format: {@code "hunt_lv:{level}"}, e.g. {@code "hunt_lv:3"}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HuntingLevelsCallbackHandler implements CallbackHandler {

    static final int MAX_LEVEL = 10;

    // Cumulative XP thresholds — must match HuntingService.XP_THRESHOLDS
    private static final int[] XP_THRESHOLDS = {
            0, 100, 350, 850, 1750, 3250, 5750, 9750, 15950, 25450
    };

    private final TelegramClient telegramClient;

    @Override
    public String getPrefix() {
        return "hunt_lv";
    }

    /** Entry point: called when player presses "📋 Уровни" button. */
    public static BotResponse buildInitialMessage(Player player) {
        int startLevel  = player != null ? player.getForest().getHunterLevel() : 1;
        int playerLevel = startLevel;
        int playerXp    = player != null ? player.getForest().getHunterXp() : 0;

        return new BotResponse(
                buildPageText(startLevel, playerLevel, playerXp),
                null, buildKeyboard(startLevel), null, null, "HTML"
        );
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

        int playerLevel = player != null ? player.getForest().getHunterLevel() : 0;
        int playerXp    = player != null ? player.getForest().getHunterXp()    : 0;

        try {
            telegramClient.execute(EditMessageText.builder()
                    .chatId(chatId)
                    .messageId(messageId)
                    .text(buildPageText(page, playerLevel, playerXp))
                    .parseMode("HTML")
                    .replyMarkup(buildKeyboard(page))
                    .build());
        } catch (TelegramApiException e) {
            log.error("Failed to edit hunt levels message", e);
        }
    }

    // ── Page builder ──────────────────────────────────────────────────────────

    private static String buildPageText(int level, int playerLevel, int playerXp) {
        String name  = HuntingService.levelName(level);
        String bonus = HuntingService.levelUnlockText(level);
        int xpReq    = XP_THRESHOLDS[level - 1];
        int xpNext   = level < MAX_LEVEL ? XP_THRESHOLDS[level] : -1;

        StringBuilder sb = new StringBuilder();

        // Header
        if (level == playerLevel) {
            sb.append("📍 <b>").append(name).append("</b> · Уровень ").append(level).append(" ← вы здесь");
        } else if (level < playerLevel) {
            sb.append("✅ <b>").append(name).append("</b> · Уровень ").append(level);
        } else {
            sb.append("🔒 <b>").append(name).append("</b> · Уровень ").append(level);
        }
        sb.append("\n\n");

        // XP thresholds
        sb.append("⭐ Нужно XP: <b>").append(xpReq).append("</b>");
        if (xpNext >= 0) {
            sb.append("  →  следующий: <b>").append(xpNext).append("</b>");
        } else {
            sb.append("  (максимальный уровень)");
        }

        // Personal progress
        if (level == playerLevel && xpNext >= 0) {
            int remaining = xpNext - playerXp;
            sb.append("\n📊 Ваш XP: ").append(playerXp).append(" / ").append(xpNext);
            sb.append(remaining <= 0 ? "  ✅ готово к повышению!" : "  (осталось: " + remaining + ")");
        } else if (level > playerLevel) {
            int remaining = xpReq - playerXp;
            if (remaining > 0) {
                sb.append("\n📊 До этого уровня: ещё <b>").append(remaining).append(" XP</b>");
            }
        }

        // Bonus
        sb.append("\n\n");
        if (bonus != null) {
            sb.append("🎁 <b>Бонус уровня:</b>\n").append(bonus);
        } else {
            sb.append("🎁 <b>Бонус:</b> базовый уровень");
        }

        sb.append("\n\n<i>").append(level).append(" / ").append(MAX_LEVEL).append("</i>");
        return sb.toString();
    }

    // ── Keyboard ──────────────────────────────────────────────────────────────

    private static InlineKeyboardMarkup buildKeyboard(int current) {
        int prev = current > 1       ? current - 1 : MAX_LEVEL;
        int next = current < MAX_LEVEL ? current + 1 : 1;

        InlineKeyboardRow row = new InlineKeyboardRow();
        row.add(InlineKeyboardButton.builder().text("◀️").callbackData("hunt_lv:" + prev).build());
        row.add(InlineKeyboardButton.builder().text("▶️").callbackData("hunt_lv:" + next).build());
        return InlineKeyboardMarkup.builder().keyboardRow(row).build();
    }
}
