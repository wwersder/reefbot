package com.reefbot.service.game.handlers;

import com.reefbot.dto.BotResponse;
import com.reefbot.entity.Island;
import com.reefbot.entity.Player;
import com.reefbot.enums.ForestActivity;
import com.reefbot.enums.PlayerScreen;
import com.reefbot.enums.ZoneType;
import com.reefbot.repository.PlayerRepository;
import com.reefbot.service.game.ForestService;
import com.reefbot.service.game.ForestService.ForestResult;
import com.reefbot.service.game.GameHandler;
import com.reefbot.util.KeyboardBuilder;
import com.reefbot.util.RichText;
import com.reefbot.util.XpBar;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboard;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardButton;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

@Component
@RequiredArgsConstructor
public class ForestZoneHandler implements GameHandler {

    public static final String BTN_CHOP    = "🪓 Рубить деревья";
    public static final String BTN_GATHER  = "🍄 Собирать";
    public static final String BTN_COLLECT = "✅ Забрать добычу";
    public static final String BTN_BACK    = "◀️ На остров";

    private static final List<String> FLAVOR = List.of(
            "Густой лес уходит вглубь острова.\nПахнет смолой и влажной землёй.",
            "Сквозь кроны пробивается солнечный свет.\nГде-то стучит дятел.",
            "Лесной полог тихо шелестит на ветру.\nВ тени прохладно и спокойно.",
            "Папоротники скрывают лесные тропинки.\nСтарые деревья помнят рассвет острова.",
            "Смолистый запах и гул ветра в соснах.\nВ полдень лес хранит тишину.",
            "Туман стелется по низинам.\nЛес сегодня особенно тихий.",
            "Запах грибов и мокрой хвои.\nГде-то журчит маленький ручей."
    );

    private final ForestService forestService;
    private final PlayerRepository playerRepository;

    @Override
    public PlayerScreen getScreen() {
        return PlayerScreen.ZONE_FOREST;
    }

    @Override
    public BotResponse handle(Player player, Island island, String text) {
        return switch (text) {
            case BTN_CHOP    -> startChopping(player);
            case BTN_GATHER  -> startGathering(player);
            case BTN_COLLECT -> collectYield(player, island);
            case BTN_BACK    -> goBack(player, island);
            default          -> buildZoneScreen(player, forestService);
        };
    }

    // ── Actions ───────────────────────────────────────────────────────────────

    private BotResponse startChopping(Player player) {
        if (!forestService.isIdle(player)) return buildZoneScreen(player, forestService);
        player.getState().setCurrentScreen(PlayerScreen.ZONE_FOREST_CHOPPING);
        playerRepository.save(player);
        return ForestChoppingHandler.buildChoppingMenu();
    }

    private BotResponse startGathering(Player player) {
        if (!forestService.isIdle(player)) return buildZoneScreen(player, forestService);
        forestService.startActivity(player, ForestActivity.GATHER);
        return buildZoneScreen(player, forestService);
    }

    private BotResponse collectYield(Player player, Island island) {
        if (!forestService.isReady(player)) return buildZoneScreen(player, forestService);

        ForestResult result = forestService.collectYield(player, island);
        player.getState().setCurrentScreen(PlayerScreen.ZONE_FOREST);
        playerRepository.save(player);

        BotResponse zoneScreen  = buildZoneScreen(player, forestService);
        BotResponse resultMsg   = buildResultMessage(result);

        // Chain: result → [event] → [level-up] → zone
        BotResponse tail = zoneScreen;
        if (result.leveledUp()) {
            tail = buildLevelUpMessage(result).withFollowUp(tail);
        }
        if (result.hasEvent()) {
            tail = buildEventMessage(result).withFollowUp(tail);
        }
        return resultMsg.withFollowUp(tail);
    }

    private BotResponse goBack(Player player, Island island) {
        player.getState().setCurrentScreen(PlayerScreen.MAIN);
        playerRepository.save(player);
        return MainMenuHandler.showMainMenu(player, island);
    }

    // ── Static builders (called from ForestChoppingHandler too) ──────────────

    public static BotResponse buildZoneScreen(Player player, ForestService forestService) {
        String flavor = FLAVOR.get(ThreadLocalRandom.current().nextInt(FLAVOR.size()));
        RichText rt = new RichText();
        rt.bold("🌲 Лес").add("\n\n").add(flavor).add("\n\n");

        int level  = player.getForest().getLevel();
        int xp     = player.getForest().getXp();
        int xpNext = forestService.xpForNextLevel(level);
        int xpPrev = forestService.xpForLevel(level);

        rt.bold("Лесоруб:").add(" " + ForestService.levelName(level) + " (ур. " + level + ")\n");
        rt.code(XpBar.render(xp - xpPrev, xpNext - xpPrev > 0 ? xpNext - xpPrev : xpNext == 0 ? 0 : xpNext));
        rt.add("\n\n");

        appendActivityStatus(rt, player, forestService);

        return rt.build(ZoneType.FOREST.getBannerPath(), keyboard(player, forestService));
    }

    private static void appendActivityStatus(RichText rt, Player player, ForestService forestService) {
        if (forestService.isActive(player)) {
            ForestActivity act = player.getForest().getActivity();
            String actName = act != null ? act.getDisplayName() : "Вылазка";
            rt.bold("⏳ " + actName + ":").add(" ещё " + forestService.timeRemainingText(player));
        } else if (forestService.isReady(player)) {
            rt.bold("✅ Добыча ждёт!").add(" Нажми — забери.");
        } else {
            rt.add("Лес свободен. Что будешь делать?");
        }
    }

    private static ReplyKeyboard keyboard(Player player, ForestService forestService) {
        KeyboardBuilder kb = KeyboardBuilder.builder();
        if (forestService.isActive(player)) {
            kb.row(new KeyboardButton(BTN_BACK));
        } else if (forestService.isReady(player)) {
            KeyboardButton collect = new KeyboardButton(BTN_COLLECT);
            collect.setStyle("success");
            kb.row(collect);
            kb.row(new KeyboardButton(BTN_BACK));
        } else {
            kb.row(BTN_CHOP, BTN_GATHER);
            kb.row(new KeyboardButton(BTN_BACK));
        }
        return kb.build();
    }

    // ── Message builders ──────────────────────────────────────────────────────

    private static BotResponse buildResultMessage(ForestResult result) {
        StringBuilder sb = new StringBuilder();
        sb.append("🌲 <b>Вылазка завершена</b>\n\n");
        sb.append("<i>").append(result.narrative()).append("</i>\n\n");

        if (result.woodGained() > 0) {
            sb.append("🪵 Дерево: <b>+").append(result.woodGained()).append("</b>\n");
        }
        if (result.shellsGained() > 0) {
            sb.append("🐚 Ракушки: <b>+").append(result.shellsGained()).append("</b>\n");
        }
        sb.append("⭐ Опыт: <b>+").append(result.xpEarned()).append("</b>");

        return BotResponse.html(sb.toString());
    }

    private static BotResponse buildEventMessage(ForestResult result) {
        return BotResponse.html("🎲 <b>Событие</b>\n\n" + result.event().text());
    }

    private static BotResponse buildLevelUpMessage(ForestResult result) {
        StringBuilder sb = new StringBuilder();
        sb.append("🎉 <b>Новый уровень лесоруба!</b>\n\n");
        sb.append("Теперь ты — <b>").append(ForestService.levelName(result.newLevel())).append("</b>");
        sb.append(" (ур. ").append(result.newLevel()).append(")");
        String unlock = ForestService.levelUnlockText(result.newLevel());
        if (unlock != null) sb.append("\n\n✨ ").append(unlock);
        return BotResponse.html(sb.toString());
    }
}
