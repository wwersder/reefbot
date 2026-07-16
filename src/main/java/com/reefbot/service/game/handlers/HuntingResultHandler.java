package com.reefbot.service.game.handlers;

import com.reefbot.dto.BotResponse;
import com.reefbot.entity.Island;
import com.reefbot.entity.Player;
import com.reefbot.enums.HuntingSpot;
import com.reefbot.enums.PlayerScreen;
import com.reefbot.repository.PlayerRepository;
import com.reefbot.service.game.GameHandler;
import com.reefbot.service.game.HuntingService;
import com.reefbot.service.game.HuntingService.HuntResult;
import com.reefbot.service.game.HuntingService.InteractiveHuntEvent;
import com.reefbot.service.game.HuntingService.RolledEvent;
import com.reefbot.util.KeyboardBuilder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;

@Component
@RequiredArgsConstructor
public class HuntingResultHandler implements GameHandler {

    public static final String BTN_COLLECT = "✅ Забрать добычу";
    public static final String BTN_LATER   = "↩️ Потом";

    private final HuntingService huntingService;
    private final PlayerRepository playerRepository;

    @Override
    public PlayerScreen getScreen() {
        return PlayerScreen.ZONE_FOREST_HUNT_RESULT;
    }

    @Override
    public BotResponse handle(Player player, Island island, String text) {
        if (BTN_LATER.equals(text)) {
            player.getState().setCurrentScreen(PlayerScreen.ZONE_FOREST);
            playerRepository.save(player);
            return ForestZoneHandler.buildZoneScreen(player, huntingService);
        }
        if (!BTN_COLLECT.equals(text)) {
            return buildResultScreen(player);
        }

        // Roll interactive event before collecting
        HuntingSpot spot = player.getForest().getHuntingSpot();
        long seed = player.getForest().getFinishAt() != null
                ? player.getForest().getFinishAt().toEpochSecond(java.time.ZoneOffset.UTC)
                : 0L;
        RolledEvent rolled = HuntingService.rollInteractiveEvent(spot != null ? spot : HuntingSpot.EDGE, seed);

        if (rolled != null) {
            // Show event choice — do NOT collect yet; player must pick A or B
            return buildEventChoiceScreen(rolled.event(), rolled.index());
        }

        // No event — collect directly
        HuntResult result = huntingService.collectYield(player, island);
        player.getState().setCurrentScreen(PlayerScreen.ZONE_FOREST);
        playerRepository.save(player);

        return buildCollectedResponse(result, player, huntingService);
    }

    // ── Event choice screen ───────────────────────────────────────────────────

    private static BotResponse buildEventChoiceScreen(InteractiveHuntEvent event, int idx) {
        String text = "🎲 <b>Событие!</b>\n\n"
                + event.narrative()
                + "\n\n<i>Что делаешь?</i>";

        InlineKeyboardRow row = new InlineKeyboardRow();
        row.add(InlineKeyboardButton.builder()
                .text("🎲 " + event.choiceALabel())
                .callbackData("hunt_ev:" + idx + ":a")
                .build());
        row.add(InlineKeyboardButton.builder()
                .text("🛡 " + event.choiceBLabel())
                .callbackData("hunt_ev:" + idx + ":b")
                .build());
        InlineKeyboardMarkup kb = InlineKeyboardMarkup.builder().keyboardRow(row).build();

        return new BotResponse(text, null, kb, null, null, "HTML");
    }

    // ── Collect response chain ────────────────────────────────────────────────

    public static BotResponse buildCollectedResponse(HuntResult result, Player player, HuntingService huntingService) {
        StringBuilder sb = new StringBuilder();
        sb.append("🏹 <b>Охота завершена!</b>\n\n");
        sb.append("<i>").append(result.narrative()).append("</i>\n\n");

        if (result.meatGained() > 0) {
            sb.append("🥩 Мясо: <b>+").append(result.meatGained()).append("</b>\n");
        }
        if (result.furGained() > 0) {
            sb.append("🪶 Мех: <b>+").append(result.furGained()).append("</b>");
            if (result.doubleFur()) sb.append(" <i>(двойной мех!)</i>");
            sb.append("\n");
        }
        sb.append("⭐ Опыт: <b>+").append(result.xpEarned()).append("</b>");
        sb.append("  (").append(result.totalXp()).append(" накоплено)");

        BotResponse collectMsg = BotResponse.html(sb.toString());
        BotResponse forestZone = ForestZoneHandler.buildZoneScreen(player, huntingService);

        BotResponse tail = forestZone;

        if (result.leveledUp()) {
            tail = buildLevelUpMessage(result.newLevel()).withFollowUp(tail);
        }

        return collectMsg.withFollowUp(tail);
    }

    private static BotResponse buildLevelUpMessage(int newLevel) {
        String name   = HuntingService.levelName(newLevel);
        String unlock = HuntingService.levelUnlockText(newLevel);
        String bonus  = unlock != null
                ? "<b>" + unlock + "</b>"
                : "Продолжай охотиться — впереди ещё много открытий.";
        String text = "🎊 Уровень охотника повышен!\n\n"
                + name + " · Уровень " + newLevel + "\n\n"
                + bonus;
        return BotResponse.html(text);
    }

    // ── Static builder (called from ForestZoneHandler on redirect) ───────────

    public static BotResponse buildResultScreen(Player player) {
        HuntingSpot spot = player.getForest().getHuntingSpot();
        String spotName = spot != null ? spot.getDisplayName() : "лес";
        String text = "🔔 Охота завершена!\n\nУгодье: " + spotName
                + "\nЖми «Забрать» чтобы получить добычу.";
        return new BotResponse(text, null,
                KeyboardBuilder.builder().row(BTN_COLLECT).row(BTN_LATER).build());
    }
}
