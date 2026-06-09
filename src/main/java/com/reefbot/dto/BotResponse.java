package com.reefbot.dto;

import org.telegram.telegrambots.meta.api.objects.MessageEntity;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboard;

import java.util.List;

public record BotResponse(
        String text,
        String photoPath,
        ReplyKeyboard keyboard,
        List<MessageEntity> entities,
        BotResponse followUp
) {
    // ── Convenience constructors ────────────────────────────────────────────

    public BotResponse(String text) {
        this(text, null, null, null, null);
    }

    public BotResponse(String text, List<MessageEntity> entities) {
        this(text, null, null, entities, null);
    }

    public BotResponse(String text, String photoPath, ReplyKeyboard keyboard) {
        this(text, photoPath, keyboard, null, null);
    }

    public BotResponse(String text, String photoPath, ReplyKeyboard keyboard, List<MessageEntity> entities) {
        this(text, photoPath, keyboard, entities, null);
    }

    /** Builder-style: attach a follow-up message sent right after this one. */
    public BotResponse withFollowUp(BotResponse next) {
        return new BotResponse(text, photoPath, keyboard, entities, next);
    }
}
