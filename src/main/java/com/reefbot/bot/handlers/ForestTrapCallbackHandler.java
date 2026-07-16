package com.reefbot.bot.handlers;

import com.reefbot.bot.CallbackHandler;
import com.reefbot.entity.Player;
import com.reefbot.entity.PlayerForest;
import com.reefbot.enums.HuntingSpot;
import com.reefbot.repository.PlayerRepository;
import com.reefbot.service.game.ForestEventService;
import com.reefbot.service.game.ForestEventService.TrapOption;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.util.List;

/**
 * Resolves the player's trap placement choice during a beast sighting.
 *
 * <p>Callback data format: {@code "forest_trap:{trapIdx}"}<br>
 * where {@code trapIdx} is 0, 1, or 2 (matching the inline buttons in
 * {@link ForestEventService#buildTrapChoiceScreen}).
 *
 * <p>Flow:
 * <ol>
 *   <li>Parse trap index from payload.</li>
 *   <li>Guard: sighting must be claimed (button was pressed) and trap not yet set.</li>
 *   <li>Store {@code sightingTrapChoice} in {@code PlayerForest}.</li>
 *   <li>Edit the inline message with a confirmation ("Ловушка поставлена").</li>
 *   <li>Trap rolls at collect time in {@link com.reefbot.service.game.HuntingService#collectYield}.</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ForestTrapCallbackHandler implements CallbackHandler {

    private final TelegramClient    telegramClient;
    private final PlayerRepository  playerRepository;

    @Override
    public String getPrefix() {
        return "forest_trap";
    }

    @Override
    public void handle(String payload, long chatId, int messageId, Player player) {
        if (player == null) return;

        int trapIdx;
        try {
            trapIdx = Integer.parseInt(payload.trim());
        } catch (NumberFormatException e) {
            log.warn("Malformed forest_trap payload: {}", payload);
            return;
        }
        if (trapIdx < 0 || trapIdx > 2) {
            log.warn("Out-of-range trap index: {}", trapIdx);
            return;
        }

        PlayerForest forest = player.getForest();

        // Guard: must be in an active hunt
        if (forest == null || forest.getFinishAt() == null) {
            editMessage(chatId, messageId, "⚠️ Охота уже завершена — ловушка не нужна.");
            return;
        }

        // Guard: already chose a trap — show confirmation of prior choice
        if (forest.getSightingTrapChoice() != null) {
            HuntingSpot spot = forest.getHuntingSpot() != null ? forest.getHuntingSpot() : HuntingSpot.EDGE;
            TrapOption prior = ForestEventService.trapPool(spot).get(forest.getSightingTrapChoice());
            editMessage(chatId, messageId,
                    "✅ <b>Ловушка уже поставлена</b>\n\n"
                    + prior.emoji() + " " + prior.locationName() + "\n"
                    + "<i>Результат — при сборе добычи.</i>");
            return;
        }

        HuntingSpot spot = forest.getHuntingSpot() != null ? forest.getHuntingSpot() : HuntingSpot.EDGE;
        List<TrapOption> traps = ForestEventService.trapPool(spot);
        TrapOption chosen = traps.get(trapIdx);

        forest.setSightingTrapChoice(trapIdx);
        playerRepository.save(player);

        String html = "✅ <b>Ловушка поставлена</b>\n\n"
                + chosen.emoji() + " " + chosen.locationName() + "\n"
                + "Шанс успеха: <b>" + chosen.chance() + "%</b>\n"
                + "Возможная добыча: <b>+" + chosen.meatBonus() + " 🥩"
                + (chosen.furBonus() > 0 ? " +" + chosen.furBonus() + " 🪶" : "")
                + "</b>\n\n"
                + "<i>Результат узнаешь когда вернёшься с охоты.</i>";

        editMessage(chatId, messageId, html);
    }

    private void editMessage(long chatId, int messageId, String html) {
        try {
            telegramClient.execute(EditMessageText.builder()
                    .chatId(chatId)
                    .messageId(messageId)
                    .text(html)
                    .parseMode("HTML")
                    .build());
        } catch (TelegramApiException e) {
            log.error("Failed to edit forest_trap message", e);
        }
    }
}
