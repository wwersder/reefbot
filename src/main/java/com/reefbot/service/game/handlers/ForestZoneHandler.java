package com.reefbot.service.game.handlers;

import com.reefbot.dto.BotResponse;
import com.reefbot.entity.Island;
import com.reefbot.entity.Player;
import com.reefbot.enums.PlayerScreen;
import com.reefbot.enums.ZoneType;
import com.reefbot.repository.PlayerRepository;
import com.reefbot.service.game.GameHandler;
import com.reefbot.service.game.HuntingService;
import com.reefbot.util.KeyboardBuilder;
import com.reefbot.util.RichText;
import com.reefbot.util.XpBar;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardButton;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

@Component
@RequiredArgsConstructor
public class ForestZoneHandler implements GameHandler {

    public static final String BTN_HUNT = "🏹 На охоту";
    public static final String BTN_BACK = "◀️ На остров";

    private static final List<String> FLAVOR = List.of(
            "Густой лес уходит вглубь острова.\nПахнет смолой и влажной землёй.",
            "Сквозь кроны пробивается солнечный свет.\nГде-то стучит дятел.",
            "Лесной полог тихо шелестит на ветру.\nВ тени прохладно и спокойно.",
            "Папоротники скрывают лесные тропинки.\nСтарые деревья помнят рассвет острова.",
            "Смолистый запах и гул ветра в соснах.\nВ полдень лес хранит тишину.",
            "Туман стелется по низинам.\nЛес сегодня особенно тихий.",
            "Запах влажной хвои и земли.\nГде-то журчит маленький ручей."
    );

    private final HuntingService huntingService;
    private final PlayerRepository playerRepository;

    @Override
    public PlayerScreen getScreen() {
        return PlayerScreen.ZONE_FOREST;
    }

    @Override
    public BotResponse handle(Player player, Island island, String text) {
        // Redirect if a hunt is already running — prevents nav abuse
        if (huntingService.isActive(player)) {
            player.getState().setCurrentScreen(PlayerScreen.ZONE_FOREST_HUNT_ACTIVE);
            playerRepository.save(player);
            return HuntingActiveHandler.buildStatusScreen(player, huntingService);
        }
        if (huntingService.isReady(player)) {
            player.getState().setCurrentScreen(PlayerScreen.ZONE_FOREST_HUNT_RESULT);
            playerRepository.save(player);
            return HuntingResultHandler.buildResultScreen(player);
        }

        return switch (text) {
            case BTN_HUNT -> goHunt(player);
            case BTN_BACK -> goBack(player, island);
            default       -> buildZoneScreen(player, huntingService);
        };
    }

    private BotResponse goHunt(Player player) {
        player.getState().setCurrentScreen(PlayerScreen.ZONE_FOREST_HUNT_MENU);
        playerRepository.save(player);
        return HuntingMenuHandler.buildHuntingMenu(player);
    }

    private BotResponse goBack(Player player, Island island) {
        player.getState().setCurrentScreen(PlayerScreen.MAIN);
        playerRepository.save(player);
        return MainMenuHandler.showMainMenu(player, island);
    }

    // ── Static helpers (called from MainMenuHandler) ─────────────────────────

    public static BotResponse buildZoneScreen(Player player, HuntingService huntingService) {
        String flavor = FLAVOR.get(ThreadLocalRandom.current().nextInt(FLAVOR.size()));
        RichText rt = new RichText();
        rt.bold("🌲 Лес").add("\n\n").add(flavor).add("\n\n");

        int level  = player.getForest().getHunterLevel();
        int xp     = player.getForest().getHunterXp();
        int xpNext = huntingService.xpForNextLevel(level);
        int xpPrev = huntingService.xpForLevel(level);

        rt.bold("Охотник:").add(" " + HuntingService.levelName(level) + " (ур. " + level + ")\n");
        if (xpNext > 0) {
            rt.code(XpBar.render(xp - xpPrev, xpNext - xpPrev));
        } else {
            rt.code("★ МАКС. УРОВЕНЬ");
        }
        rt.add("\n\nЛес ждёт. Выбери угодье и начни охоту.");

        KeyboardBuilder kb = KeyboardBuilder.builder();
        kb.row(BTN_HUNT);
        kb.row(new KeyboardButton(BTN_BACK));

        return rt.build(ZoneType.FOREST.getBannerPath(), kb.build());
    }
}
