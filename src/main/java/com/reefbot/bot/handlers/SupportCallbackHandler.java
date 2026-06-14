package com.reefbot.bot.handlers;

import com.reefbot.bot.CallbackHandler;
import com.reefbot.entity.Player;
import com.reefbot.service.support.SupportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageReplyMarkup;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

/**
 * Handles inline button callbacks with prefix "support".
 *
 * Payloads:
 *   resolve:<ticketId>  — close ticket from the support group inline button
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SupportCallbackHandler implements CallbackHandler {

    private final SupportService supportService;
    private final TelegramClient telegramClient;

    @Override
    public String getPrefix() {
        return "support";
    }

    @Override
    public void handle(String payload, long chatId, int messageId, Player player) {
        String[] parts = payload.split(":", 2);
        String action = parts[0];

        switch (action) {
            case "resolve" -> handleResolve(parts, chatId, messageId, player);
            default -> log.warn("Unknown support callback action: {}", action);
        }
    }

    private void handleResolve(String[] parts, long chatId, int messageId, Player player) {
        if (parts.length < 2) {
            log.warn("Missing ticket ID in support resolve callback");
            return;
        }

        long ticketId;
        try {
            ticketId = Long.parseLong(parts[1]);
        } catch (NumberFormatException e) {
            log.warn("Invalid ticket ID in resolve callback: {}", parts[1]);
            return;
        }

        // Player is null here because the callback comes from the support group, not from a player's chat.
        // Use the telegram ID from the callback sender (passed via chatId which is the chat, not sender).
        // The CallbackDispatcher already loaded the player for us — but in the group context player may be null.
        // We need the sender's telegram ID. It's available via the player object if they're registered.
        // If player is null (staff not a player), we can't proceed here cleanly.
        // Workaround: store sender ID in callback data. Since CallbackDispatcher gives us player by telegramId,
        // if staff is also a player the telegramId is known; if not, log and skip.
        if (player == null) {
            log.warn("Resolve callback from unregistered player/staff, ticketId={}", ticketId);
            return;
        }

        String result = supportService.resolveTicket(ticketId, player.getTelegramId());
        log.info("Inline resolve ticket #{}: {}", ticketId, result);

        // Remove the inline keyboard from the ticket message after action
        if (result.startsWith("✅")) {
            try {
                telegramClient.execute(EditMessageReplyMarkup.builder()
                        .chatId(chatId)
                        .messageId(messageId)
                        .build());
            } catch (TelegramApiException e) {
                log.warn("Failed to remove keyboard from resolved ticket message", e);
            }
        }
    }
}
