package com.reefbot.util;

import java.text.NumberFormat;
import java.util.Locale;

/** Number formatting utilities for player-facing text. */
public final class Fmt {

    private static final ThreadLocal<NumberFormat> NF =
            ThreadLocal.withInitial(() -> NumberFormat.getNumberInstance(Locale.forLanguageTag("de")));

    private Fmt() {}

    /** Format integer with thousand-group separators (Russian locale → spaces). */
    public static String n(long v) { return NF.get().format(v); }
    public static String n(int v)  { return NF.get().format(v); }
}
