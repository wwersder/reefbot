package com.reefbot.bot.handlers;

import com.reefbot.bot.CallbackHandler;
import com.reefbot.entity.Island;
import com.reefbot.entity.Player;
import com.reefbot.enums.HuntingSpot;
import com.reefbot.enums.PlayerScreen;
import com.reefbot.repository.PlayerRepository;
import com.reefbot.service.game.HuntingService;
import com.reefbot.service.game.HuntingService.EventOutcome;
import com.reefbot.service.game.HuntingService.HuntResult;
import com.reefbot.service.game.HuntingService.InteractiveHuntEvent;
import com.reefbot.service.game.HuntingService.RolledEvent;
import com.reefbot.service.game.handlers.HuntingResultHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.MessageEntity;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.util.List;
import java.util.Random;

/**
 * Resolves interactive hunt events triggered during collect.
 *
 * <p>Callback data format: {@code "hunt_ev:{eventIdx}:{choice}"}<br>
 * where {@code choice} is {@code "a"} (risky) or {@code "b"} (safe).
 *
 * <p>Flow:
 * <ol>
 *   <li>Parse eventIdx + choice from payload.</li>
 *   <li>Re-derive the same event via seeded random (deterministic, same seed as HuntingResultHandler).</li>
 *   <li>Resolve choice A via fresh random; choice B is always deterministic.</li>
 *   <li>Call {@code huntingService.collectYield(...)} with event modifiers.</li>
 *   <li>Edit the inline choice message with the outcome narrative.</li>
 *   <li>Send the loot summary, optional level-up, and forest zone screen as new messages.</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HuntEventCallbackHandler implements CallbackHandler {

    private final HuntingService huntingService;
    private final TelegramClient telegramClient;
    private final PlayerRepository playerRepository;

    @Override
    public String getPrefix() {
        return "hunt_ev";
    }

    @Override
    public void handle(String payload, long chatId, int messageId, Player player) {
        if (player == null) return;

        // Guard: hunt must still be in result state
        HuntingSpot spot = player.getForest() != null ? player.getForest().getHuntingSpot() : null;
        if (spot == null || player.getForest().getFinishAt() == null) {
            // Already collected — edit the stale message silently
            editMessage(chatId, messageId, "✅ Добыча уже собрана.");
            return;
        }

        // Parse payload: "{eventIdx}:{choice}"
        String[] parts = payload.split(":", 2);
        if (parts.length < 2) {
            log.warn("Malformed hunt_ev payload: {}", payload);
            return;
        }
        int eventIdx;
        try {
            eventIdx = Integer.parseInt(parts[0]);
        } catch (NumberFormatException e) {
            log.warn("Non-numeric event index in hunt_ev payload: {}", payload);
            return;
        }
        boolean choiceA = "a".equals(parts[1]);

        // Re-derive event deterministically (same seed as HuntingResultHandler used)
        long seed = player.getForest().getFinishAt().toEpochSecond(java.time.ZoneOffset.UTC);
        RolledEvent rolled = HuntingService.rollInteractiveEvent(spot, seed);
        if (rolled == null || rolled.index() != eventIdx) {
            log.warn("hunt_ev event mismatch: expected {}, got {}", eventIdx, rolled == null ? "null" : rolled.index());
            editMessage(chatId, messageId, "⚠️ Событие устарело — попробуй собрать добычу снова.");
            return;
        }

        InteractiveHuntEvent event = rolled.event();
        EventOutcome outcome;
        if (choiceA) {
            boolean success = new Random().nextInt(100) < event.successChanceA();
            outcome = success ? event.outcomeASuccess() : event.outcomeAFail();
        } else {
            outcome = event.outcomeB();
        }

        // Collect yield with event modifiers
        Island island = player.getIsland();
        HuntResult result = huntingService.collectYield(
                player, island, outcome.meatDelta(), outcome.furDelta(), outcome.xpDelta());

        player.getState().setCurrentScreen(PlayerScreen.ZONE_FOREST);
        playerRepository.save(player);

        // 1. Edit the inline choice message with outcome narrative
        editMessage(chatId, messageId, "🎲 <b>Исход события</b>\n\n" + outcome.text());

        // 2. Send the full collect result chain
        sendChain(chatId, HuntingResultHandler.buildCollectedResponse(result, player, huntingService));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void editMessage(long chatId, int messageId, String html) {
        try {
            telegramClient.execute(EditMessageText.builder()
                    .chatId(chatId)
                    .messageId(messageId)
                    .text(html)
                    .parseMode("HTML")
                    .build());
        } catch (TelegramApiException e) {
            log.error("Failed to edit hunt event message", e);
        }
    }

    /** Recursively sends a BotResponse chain (text-only; photos are sent without the banner). */
    private void sendChain(long chatId, com.reefbot.dto.BotResponse response) {
        if (response == null) return;
        try {
            SendMessage.SendMessageBuilder<?, ?> b = SendMessage.builder()
                    .chatId(chatId)
                    .text(response.text());

            if (response.keyboard() != null) b.replyMarkup(response.keyboard());
            if (response.parseMode() != null) {
                b.parseMode(response.parseMode());
            } else {
                List<MessageEntity> entities = response.entities();
                if (entities != null && !entities.isEmpty()) b.entities(entities);
            }

            telegramClient.execute(b.build());
        } catch (TelegramApiException e) {
            log.error("Failed to send hunt event result message", e);
            return;
        }
        sendChain(chatId, response.followUp());
    }
}
