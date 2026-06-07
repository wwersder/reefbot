package com.reefbot.dto;

import org.telegram.telegrambots.meta.api.objects.MessageEntity;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboard;

import java.util.List;

public record BotResponse(
        String text,
        String photoPath,
        ReplyKeyboard keyboard,
        List<MessageEntity> entities
) {
    public BotResponse(String text, String photoPath, ReplyKeyboard keyboard) {
        this(text, photoPath, keyboard, null);
    }

    public BotResponse(String text, String photoPath, ReplyKeyboard keyboard, List<MessageEntity> entities) {
        this.text = text;
        this.photoPath = photoPath;
        this.keyboard = keyboard;
        this.entities = entities;
    }

    public BotResponse(String text) {
        this(text, null, null, null);
    }

    public BotResponse(String text, List<MessageEntity> entities) {
        this(text, null, null, entities);
    }
}
