package com.reefbot.bot.handlers;

import com.reefbot.bot.CallbackHandler;
import com.reefbot.entity.Player;
import com.reefbot.service.game.FishingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

@Slf4j
@Component
@RequiredArgsConstructor
public class BonusCallbackHandler implements CallbackHandler {

    public static final String HINT_TEXT = "✨ У вас активны бонусы рыбака";

    private final TelegramClient telegramClient;

    @Override
    public String getPrefix() {
        return "bonus";
    }

    @Override
    public void handle(String payload, long chatId, int messageId, Player player) {
        boolean showDetails = "show".equals(payload);

        String text;
        InlineKeyboardMarkup keyboard;

        if (showDetails) {
            text = buildBonusText(player);
            keyboard = backKeyboard();
        } else {
            text = HINT_TEXT;
            keyboard = showKeyboard();
        }

        try {
            telegramClient.execute(EditMessageText.builder()
                    .chatId(chatId)
                    .messageId(messageId)
                    .text(text)
                    .parseMode("HTML")
                    .replyMarkup(keyboard)
                    .build());
        } catch (TelegramApiException e) {
            log.error("Failed to edit bonus message", e);
        }
    }

    private static String buildBonusText(Player player) {
        int level = player != null ? player.getFishing().getFishingLevel() : 1;
        int xp    = player != null ? player.getFishing().getFishingXp()    : 0;

        StringBuilder sb = new StringBuilder();
        sb.append("✨ <b>Бонусы рыбака</b>\n\n");

        // --- Level bonuses ---
        sb.append("🎣 <b>За уровень рыбака</b> (").append(FishingService.levelName(level))
          .append(" · Ур. ").append(level).append(")\n");

        if (level >= 2) sb.append("  • +1 к мин. улову на всех местах\n");
        if (level >= 4) sb.append("  • +10% XP за рыбалку\n");
        if (level >= 5) sb.append("  • +20% шанс бонусного ресурса\n");
        if (level >= 7) sb.append("  • +30% XP за рыбалку (вместо +10%)\n");
        if (level >= 8) sb.append("  • +2 к макс. улову на всех местах\n");

        if (level < 2) {
            sb.append("  Пока нет — достигни уровня 2\n");
        }

        // Next level hint
        if (level < 10) {
            int[] thresholds = {0, 50, 150, 350, 700, 1200, 2000, 3500, 6000, 10000};
            int xpToNext = thresholds[level] - xp;
            String nextBonus = FishingService.levelUnlockText(level + 1);
            if (nextBonus != null) {
                sb.append("  <i>→ Ур. ").append(level + 1).append(" (через ")
                  .append(xpToNext).append(" XP): ").append(nextBonus).append("</i>\n");
            }
        }

        // --- Donation bonuses (placeholder) ---
        sb.append("\n💎 <b>За поддержку</b>\n");
        sb.append("  Нет активных бонусов\n");

        return sb.toString();
    }

    public static InlineKeyboardMarkup showKeyboard() {
        InlineKeyboardRow row = new InlineKeyboardRow();
        row.add(InlineKeyboardButton.builder()
                .text("✨ Бонусы")
                .callbackData("bonus:show")
                .build());
        return InlineKeyboardMarkup.builder().keyboardRow(row).build();
    }

    private static InlineKeyboardMarkup backKeyboard() {
        InlineKeyboardRow row = new InlineKeyboardRow();
        row.add(InlineKeyboardButton.builder()
                .text("◀️ Назад")
                .callbackData("bonus:hide")
                .build());
        return InlineKeyboardMarkup.builder().keyboardRow(row).build();
    }
}
