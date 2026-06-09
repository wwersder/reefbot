package com.reefbot.bot.handlers;

import com.reefbot.bot.CallbackHandler;
import com.reefbot.dto.BotResponse;
import com.reefbot.entity.Player;
import com.reefbot.enums.FishingSpot;
import com.reefbot.service.game.FishingService;
import com.reefbot.service.game.handlers.FishingMenuHandler;
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

    private final TelegramClient telegramClient;

    @Override
    public String getPrefix() {
        return "bonus";
    }

    @Override
    public void handle(String payload, long chatId, int messageId, Player player) {
        if (player == null) return;

        try {
            if ("show".equals(payload)) {
                telegramClient.execute(EditMessageText.builder()
                        .chatId(chatId)
                        .messageId(messageId)
                        .text(buildBonusText(player))
                        .parseMode("HTML")
                        .replyMarkup(backKeyboard())
                        .build());
            } else {
                // hide — restore spot detail
                FishingSpot spot = player.getFishing().getFishingSpot();
                if (spot == null) return;
                BotResponse detail = FishingMenuHandler.buildSpotDetail(spot, player);
                telegramClient.execute(EditMessageText.builder()
                        .chatId(chatId)
                        .messageId(messageId)
                        .text(detail.text())
                        .replyMarkup((InlineKeyboardMarkup) detail.keyboard())
                        .build());
            }
        } catch (TelegramApiException e) {
            log.error("Failed to edit bonus message", e);
        }
    }

    private static String buildBonusText(Player player) {
        int level = player.getFishing().getFishingLevel();

        StringBuilder sb = new StringBuilder();
        sb.append("✨ <b>Бонусы рыбака</b>\n\n");
        sb.append("🎣 <b>За уровень рыбака</b>\n");
        sb.append(FishingService.levelName(level)).append(" · Ур. ").append(level).append("\n\n");

        boolean anyBonus = false;
        if (level >= 2) { sb.append("• +1 к мин. улову на всех местах\n"); anyBonus = true; }
        if (level >= 4) { sb.append("• +10% XP за рыбалку\n"); anyBonus = true; }
        if (level >= 5) { sb.append("• +20% шанс бонусного ресурса\n"); anyBonus = true; }
        if (level >= 7) { sb.append("• +30% XP (вместо +10%)\n"); anyBonus = true; }
        if (level >= 8) { sb.append("• +2 к макс. улову на всех местах\n"); anyBonus = true; }

        if (!anyBonus) {
            sb.append("Пока нет — достигни уровня 2\n");
        }

        return sb.toString();
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
