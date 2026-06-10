package com.reefbot.util;

import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardRow;

import java.util.ArrayList;
import java.util.List;

public class KeyboardBuilder {

    private final List<KeyboardRow> rows = new ArrayList<>();
    private boolean resize = true;
    private boolean oneTime = false;

    public KeyboardBuilder row(String... buttons) {
        KeyboardRow row = new KeyboardRow();
        for (String label : buttons) {
            row.add(new KeyboardButton(label));
        }
        rows.add(row);
        return this;
    }

    public KeyboardBuilder row(KeyboardButton... buttons) {
        KeyboardRow row = new KeyboardRow();
        for (KeyboardButton button : buttons) {
            row.add(button);
        }
        rows.add(row);
        return this;
    }

    public KeyboardBuilder resize(boolean value) {
        this.resize = value;
        return this;
    }

    public KeyboardBuilder oneTime(boolean value) {
        this.oneTime = value;
        return this;
    }

    public ReplyKeyboardMarkup build() {
        return ReplyKeyboardMarkup.builder()
                .keyboard(rows)
                .resizeKeyboard(resize)
                .oneTimeKeyboard(oneTime)
                .build();
    }

    /** Кнопка с кастомным эмодзи-иконкой (иконка отображается перед текстом). */
    public static KeyboardButton btn(String text, String iconCustomEmojiId) {
        KeyboardButton btn = new KeyboardButton(text);
        btn.setIconCustomEmojiId(iconCustomEmojiId);
        return btn;
    }

    public static KeyboardBuilder builder() {
        return new KeyboardBuilder();
    }
}