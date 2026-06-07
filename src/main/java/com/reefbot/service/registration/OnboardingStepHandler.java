package com.reefbot.service.registration;

import com.reefbot.dto.BotResponse;
import com.reefbot.entity.Player;
import com.reefbot.enums.OnboardingStep;
import com.reefbot.util.EmojiUtil;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboard;

import static com.reefbot.util.EmojiUtil.e;
import static com.reefbot.util.EmojiUtil.entities;

public interface OnboardingStepHandler {

    OnboardingStep getStep();

    BotResponse process(Player player, String message);

    String USE_KEYBOARD_TEXT = "⭐ Используй клавиатуру.";

    static BotResponse useKeyboard(ReplyKeyboard keyboard) {
        return new BotResponse(
                USE_KEYBOARD_TEXT,
                null,
                keyboard,
                entities(USE_KEYBOARD_TEXT, e("⭐", "5237691237124820624"))
        );
    }
}
