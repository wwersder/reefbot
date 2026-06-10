package com.reefbot.util;

/**
 * Кастомные эмодзи ReefBot.
 * <p>
 * {@code TEXT} — строка для кнопок клавиатуры и обычного текста.<br>
 * {@code DEF}  — для MessageEntity в тексте сообщений (через EmojiUtil.entities()).
 */
public final class ReefEmoji {

    // ── Зоны / места рыбалки ───────────────────────────────────────────────
    public static final String SHORE_TEXT    = "🌴";
    public static final String REEF_TEXT     = "🪸";
    public static final String OPEN_SEA_TEXT = "🌊";

    public static final EmojiUtil.Def SHORE    = EmojiUtil.e(SHORE_TEXT,    "5807538646030489502");
    public static final EmojiUtil.Def REEF     = EmojiUtil.e(REEF_TEXT,     "5325862947161909272");
    public static final EmojiUtil.Def OPEN_SEA = EmojiUtil.e(OPEN_SEA_TEXT, "5386798809286189971");

    // ── Рыбалка ───────────────────────────────────────────────────────────
    public static final String FISHING_TEXT  = "🎣";
    public static final String TIMER_TEXT    = "⏱";
    public static final String FISH_TEXT     = "🐟";
    public static final String STAR_TEXT     = "⭐️";
    public static final String SPARKLES_TEXT = "✨";
    public static final String PARTY_TEXT    = "🎉";
    public static final String CHECK_TEXT    = "✅";

    public static final EmojiUtil.Def FISHING  = EmojiUtil.e(FISHING_TEXT,  "5343609421316521960");
    public static final EmojiUtil.Def TIMER    = EmojiUtil.e(TIMER_TEXT,    "5382194935057372936");
    public static final EmojiUtil.Def FISH     = EmojiUtil.e(FISH_TEXT,     "5384574037701696503");
    public static final EmojiUtil.Def STAR     = EmojiUtil.e(STAR_TEXT,     "5438496463044752972");
    public static final EmojiUtil.Def SPARKLES = EmojiUtil.e(SPARKLES_TEXT, "5463297803235113601");
    public static final EmojiUtil.Def PARTY    = EmojiUtil.e(PARTY_TEXT,    "5436040291507247633");
    public static final EmojiUtil.Def CHECK    = EmojiUtil.e(CHECK_TEXT,    "5237699328843200968");

    // ── UI ────────────────────────────────────────────────────────────────
    public static final String LEVELS_TEXT = "📊";
    public static final EmojiUtil.Def LEVELS = EmojiUtil.e(LEVELS_TEXT, "5231200819986047254");

    private ReefEmoji() {}
}
