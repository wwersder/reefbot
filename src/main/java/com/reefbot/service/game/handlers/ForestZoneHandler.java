package com.reefbot.service.game.handlers;

import com.reefbot.bot.handlers.HuntingLevelsCallbackHandler;
import com.reefbot.dto.BotResponse;
import com.reefbot.entity.Island;
import com.reefbot.entity.Player;
import com.reefbot.enums.HuntingSpot;
import com.reefbot.enums.PlayerScreen;
import com.reefbot.enums.ZoneType;
import com.reefbot.repository.PlayerRepository;
import com.reefbot.service.game.ForestEventService;
import com.reefbot.service.game.GameHandler;
import com.reefbot.service.game.HuntingService;
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

    public static final String BTN_HUNT    = "🏹 На охоту";
    public static final String BTN_REFRESH = "🔄 Обновить";
    public static final String BTN_COLLECT = "✅ Забрать добычу";
    public static final String BTN_LEVELS  = "📋 Уровни";
    public static final String BTN_BACK    = "◀️ На остров";

    private static final List<String> FLAVOR = List.of(
            "Густой лес уходит вглубь острова.\nПахнет смолой и влажной землёй.",
            "Сквозь кроны пробивается солнечный свет.\nГде-то стучит дятел.",
            "Лесной полог тихо шелестит на ветру.\nВ тени прохладно и спокойно.",
            "Папоротники скрывают лесные тропинки.\nСтарые деревья помнят рассвет острова.",
            "Смолистый запах и гул ветра в соснах.\nВ полдень лес хранит тишину.",
            "Туман стелется по низинам.\nЛес сегодня особенно тихий.",
            "Запах влажной хвои и земли.\nГде-то журчит маленький ручей."
    );

    private final HuntingService     huntingService;
    private final ForestEventService forestEventService;
    private final PlayerRepository   playerRepository;

    @Override
    public PlayerScreen getScreen() {
        return PlayerScreen.ZONE_FOREST;
    }

    @Override
    public BotResponse handle(Player player, Island island, String text) {
        return switch (text) {
            case BTN_HUNT    -> goHunt(player);
            case BTN_REFRESH -> goActiveScreen(player);
            case BTN_COLLECT -> goResultScreen(player);
            case BTN_LEVELS  -> HuntingLevelsCallbackHandler.buildInitialMessage(player);
            case BTN_BACK    -> goBack(player, island);
            default          -> buildZoneScreen(player, huntingService);
        };
    }

    // ── Actions ──────────────────────────────────────────────────────────────

    private BotResponse goHunt(Player player) {
        if (!huntingService.isIdle(player)) return buildZoneScreen(player, huntingService);
        player.getState().setCurrentScreen(PlayerScreen.ZONE_FOREST_HUNT_MENU);
        playerRepository.save(player);
        return HuntingMenuHandler.buildHuntingMenu(player);
    }

    /** Open the detailed wait screen (from "Обновить"). */
    private BotResponse goActiveScreen(Player player) {
        if (!huntingService.isActive(player)) return buildZoneScreen(player, huntingService);
        player.getState().setCurrentScreen(PlayerScreen.ZONE_FOREST_HUNT_ACTIVE);
        playerRepository.save(player);
        return HuntingActiveHandler.buildStatusScreen(player, huntingService, forestEventService);
    }

    /** Collect yield — go to result screen. */
    private BotResponse goResultScreen(Player player) {
        if (!huntingService.isReady(player)) return buildZoneScreen(player, huntingService);
        player.getState().setCurrentScreen(PlayerScreen.ZONE_FOREST_HUNT_RESULT);
        playerRepository.save(player);
        return HuntingResultHandler.buildResultScreen(player);
    }

    private BotResponse goBack(Player player, Island island) {
        player.getState().setCurrentScreen(PlayerScreen.MAIN);
        playerRepository.save(player);
        return MainMenuHandler.showMainMenu(player, island);
    }

    // ── Static helpers (called from MainMenuHandler + HuntingResultHandler) ──

    public static BotResponse buildZoneScreen(Player player, HuntingService huntingService) {
        String flavor = FLAVOR.get(ThreadLocalRandom.current().nextInt(FLAVOR.size()));
        RichText rt = new RichText();
        rt.bold("🌲 Лес").add("\n\n").add(flavor).add("\n\n");

        int level  = player.getForest().getHunterLevel();
        int xp     = player.getForest().getHunterXp();
        int xpNext = huntingService.xpForNextLevel(level);
        int xpPrev = huntingService.xpForLevel(level);

        rt.bold("Охотник:").add(" " + HuntingService.levelName(level) + " (ур. " + level + ")\n");
        rt.code(xpNext > 0 ? XpBar.render(xp - xpPrev, xpNext - xpPrev) : "★ МАКС. УРОВЕНЬ");
        rt.add("\n\n");

        // Inline activity status
        if (huntingService.isActive(player)) {
            HuntingSpot spot = player.getForest().getHuntingSpot();
            String spotName = spot != null ? spot.getDisplayName() : "лес";
            rt.add("⏳ Охота " + spotName.toLowerCase()
                    + " — ещё " + huntingService.timeRemainingText(player));
        } else if (huntingService.isReady(player)) {
            HuntingSpot spot = player.getForest().getHuntingSpot();
            String spotName = spot != null ? spot.getDisplayName() : "лес";
            rt.add("✅ Охота " + spotName.toLowerCase() + " завершена! Добыча ждёт.");
        } else {
            rt.add("Лес свободен. Выбери угодье и начни охоту.");
        }

        return rt.build(ZoneType.FOREST.getBannerPath(), buildKeyboard(player, huntingService));
    }

    private static ReplyKeyboard buildKeyboard(Player player, HuntingService huntingService) {
        KeyboardBuilder kb = KeyboardBuilder.builder();

        if (huntingService.isActive(player)) {
            kb.row(BTN_REFRESH);
        } else if (huntingService.isReady(player)) {
            KeyboardButton collect = new KeyboardButton(BTN_COLLECT);
            collect.setStyle("success");
            kb.row(collect);
        } else {
            kb.row(BTN_HUNT, BTN_LEVELS);
        }

        kb.row(new KeyboardButton(BTN_BACK));
        return kb.build();
    }
}
