package com.reefbot.util;

import com.reefbot.dto.BotResponse;
import org.telegram.telegrambots.meta.api.objects.MessageEntity;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboard;

import java.util.ArrayList;
import java.util.List;

/**
 * Билдер Telegram-сообщений с перекрывающимися сущностями (bold + custom_emoji)
 * без использования HTML parse_mode.
 * <p>
 * Оффсеты считаются в UTF-16 code units — как Java {@code String.length()}.
 * <p>
 * Пример:
 * <pre>{@code
 * RichText rt = new RichText();
 * rt.beginBold()
 *   .emoji(ReefEmoji.FISHING).add(" Рыбалка ").emoji(ReefEmoji.SHORE).add(" У берега")
 *   .endBold()
 *   .add("\n\n")
 *   .emoji(ReefEmoji.TIMER).add(" ").bold("Время:").add(" 1 мин");
 * return rt.build(keyboard);
 * }</pre>
 */
public class RichText {

    private final StringBuilder       sb   = new StringBuilder();
    private final List<MessageEntity> ents = new ArrayList<>();
    private int boldStart = -1;

    /** Добавить обычный текст. */
    public RichText add(String s) {
        sb.append(s);
        return this;
    }

    /** Начать жирный спан (закрыть через {@link #endBold()}). */
    public RichText beginBold() {
        boldStart = sb.length();
        return this;
    }

    /** Закрыть текущий жирный спан. */
    public RichText endBold() {
        if (boldStart >= 0) {
            int len = sb.length() - boldStart;
            if (len > 0) ents.add(entity("bold", null, boldStart, len));
            boldStart = -1;
        }
        return this;
    }

    /** Добавить {@code s} как жирный текст. */
    public RichText bold(String s) {
        return beginBold().add(s).endBold();
    }

    /** Добавить {@code s} как monospace-код (entity type "code"). Хорошо смотрится для прогресс-баров. */
    public RichText code(String s) {
        int start = sb.length();
        sb.append(s);
        int len = sb.length() - start;
        if (len > 0) ents.add(entity("code", null, start, len));
        return this;
    }

    /**
     * Добавить {@code s} как цитату (blockquote entity) — серая полоса слева, как в Telegram.
     * Идеально для описаний предметов в инвентаре.
     * <p>
     * Важно: blockquote не может быть внутри bold/italic и наоборот.
     * Используй отдельным блоком.
     */
    public RichText blockquote(String s) {
        int start = sb.length();
        sb.append(s);
        int len = sb.length() - start;
        if (len > 0) ents.add(entity("blockquote", null, start, len));
        return this;
    }

    /** Добавить кастомный эмодзи (custom_emoji entity). */
    public RichText emoji(EmojiUtil.Def def) {
        int start = sb.length();
        sb.append(def.placeholder());
        ents.add(entity("custom_emoji", def.id(), start, def.placeholder().length()));
        return this;
    }

    public String getText()                  { return sb.toString(); }
    public List<MessageEntity> getEntities() { return ents; }

    public BotResponse build() {
        return new BotResponse(sb.toString(), ents);
    }

    public BotResponse build(ReplyKeyboard keyboard) {
        return new BotResponse(sb.toString(), null, keyboard, ents);
    }

    public BotResponse build(String photoPath, ReplyKeyboard keyboard) {
        return new BotResponse(sb.toString(), photoPath, keyboard, ents);
    }

    private static MessageEntity entity(String type, String customEmojiId, int offset, int length) {
        MessageEntity.MessageEntityBuilder b = MessageEntity.builder()
                .type(type)
                .offset(offset)
                .length(length);
        if (customEmojiId != null) b.customEmojiId(customEmojiId);
        return b.build();
    }
}
