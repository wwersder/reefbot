package com.reefbot.bot;

import com.reefbot.entity.Player;

/**
 * Handles a specific type of inline-button callback query.
 * Callback data format: "{prefix}:{payload}", e.g. "levels:3".
 */
public interface CallbackHandler {

    /** Prefix before the colon in callback data, e.g. "levels". */
    String getPrefix();

    /**
     * Handle the callback.
     *
     * @param payload    part of callback data after the colon
     * @param chatId     chat to edit/send in
     * @param messageId  message to edit (for EditMessageText)
     * @param player     resolved player (may be null for anonymous callbacks)
     */
    void handle(String payload, long chatId, int messageId, Player player);
}
