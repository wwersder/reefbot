package com.reefbot.dto;

import org.telegram.telegrambots.meta.api.objects.MessageEntity;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboard;

import java.util.List;

public record BotResponse(

        String text,

        String photo,

        ReplyKeyboard keyboard,

        List<MessageEntity> entities

) {
    public BotResponse(String text, String photo, ReplyKeyboard keyboard) {
        this(text, photo, keyboard, null);
    }
}