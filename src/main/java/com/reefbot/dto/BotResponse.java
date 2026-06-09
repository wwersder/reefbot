package com.reefbot.dto;

import org.telegram.telegrambots.meta.api.objects.MessageEntity;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboard;

import java.util.List;

public record BotResponse(
        String text,
        String photoPath,
        ReplyKeyboard keyboard,
        List<MessageEntity> entities,
        BotResponse followUp,
        String parseMode          // "HTML" or null
) {
    // ── Convenience constructors ────────────────────────────────────────────

    public BotResponse(String text) {
        this(text, null, null, null, null, null);
    }

    public BotResponse(String text, List<MessageEntity> entities) {
        this(text, null, null, entities, null, null);
    }

    public BotResponse(String text, String photoPath, ReplyKeyboard keyboard) {
        this(text, photoPath, keyboard, null, null, null);
    }

    public BotResponse(String text, String photoPath, ReplyKeyboard keyboard, List<MessageEntity> entities) {
        this(text, photoPath, keyboard, entities, null, null);
    }

    /** HTML-formatted message (use &lt;b&gt;, &lt;i&gt;, &lt;code&gt;, etc.). */
    public static BotResponse html(String text) {
        return new BotResponse(text, null, null, null, null, "HTML");
    }

    public static BotResponse html(String text, ReplyKeyboard keyboard) {
        return new BotResponse(text, null, keyboard, null, null, "HTML");
    }

    /** Builder-style: attach a follow-up message sent right after this one. */
    public BotResponse withFollowUp(BotResponse next) {
        return new BotResponse(text, photoPath, keyboard, entities, next, parseMode);
    }
}
