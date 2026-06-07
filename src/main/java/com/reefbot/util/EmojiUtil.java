package com.reefbot.util;

import org.telegram.telegrambots.meta.api.objects.MessageEntity;

import java.util.Arrays;
import java.util.List;

public class EmojiUtil {

    public record Def(String placeholder, String id) {}

    public static Def e(String placeholder, String id) {
        return new Def(placeholder, id);
    }

    public static List<MessageEntity> entities(String text, Def... defs) {
        return Arrays.stream(defs)
                .filter(d -> text.contains(d.placeholder()))
                .map(d -> entity(text, d))
                .toList();
    }

    private static MessageEntity entity(String text, Def d) {
        return MessageEntity.builder()
                .type("custom_emoji")
                .offset(text.indexOf(d.placeholder()))
                .length(d.placeholder().length())
                .customEmojiId(d.id())
                .build();
    }
}
