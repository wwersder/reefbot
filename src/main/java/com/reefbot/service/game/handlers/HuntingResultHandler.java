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
import com.reefbot.util.KeyboardBuilder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

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

        HuntResult result = huntingService.collectYield(player, island);
        player.getState().setCurrentScreen(PlayerScreen.ZONE_FOREST);
        playerRepository.save(player);

        return buildCollectedResponse(result, player);
    }

    private BotResponse buildCollectedResponse(HuntResult result, Player player) {
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

        int nextLevelXp = huntingService.xpForNextLevel(result.newLevel());
        if (nextLevelXp > 0) {
            sb.append("  (").append(result.totalXp()).append("/").append(nextLevelXp).append(")");
        }

        BotResponse collectMsg  = BotResponse.html(sb.toString());
        BotResponse forestZone  = ForestZoneHandler.buildZoneScreen(player, huntingService);

        BotResponse tail = forestZone;

        if (result.leveledUp()) {
            tail = buildLevelUpMessage(result.newLevel()).withFollowUp(tail);
        }
        if (result.hasEvent()) {
            tail = buildEventMessage(result).withFollowUp(tail);
        }

        return collectMsg.withFollowUp(tail);
    }

    private static BotResponse buildEventMessage(HuntResult result) {
        return BotResponse.html("🎲 <b>Событие</b>\n\n" + result.event().text());
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
