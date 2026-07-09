package com.reefbot.service.game.handlers;

import com.reefbot.dto.BotResponse;
import com.reefbot.entity.Island;
import com.reefbot.entity.IslandBuilding;
import com.reefbot.entity.Player;
import com.reefbot.enums.BuildingType;
import com.reefbot.enums.PlayerScreen;
import com.reefbot.repository.PlayerRepository;
import com.reefbot.service.game.BuildingService;
import com.reefbot.service.game.GameHandler;
import com.reefbot.service.game.TideService;
import com.reefbot.util.KeyboardBuilder;
import com.reefbot.util.RichText;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboard;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardButton;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Экран «🏖 Пляж» — прибрежная полоса со своей атмосферой.
 * Отсюда игрок прочёсывает пляж и входит в мини-игру прилива.
 * Экран: ZONE_SHORE_BEACH.
 */
@Component
@RequiredArgsConstructor
public class ShoreBeachHandler implements GameHandler {

    public static final String BTN_TIDE = "🌊 Прилив!";
    public static final String BTN_SCAN = "🏖 Прочесать пляж";
    public static final String BTN_BACK = "◀️ На берег";

    private static final List<String> FLAVOR = List.of(
            "Мелкий песок скрипит под ногами. Волна принесла обломок дерева — и что-то ещё, поблёскивающее под водой.",
            "Прибой откатил, обнажив полосу мокрого песка. Где-то под водорослями прячется что-то интересное.",
            "Берег пахнет йодом и мокрым камнем. Волны шепчут тихо, почти как дыхание.",
            "Сухие водоросли хрустят под ногами. Здесь явно что-то было — и, может, осталось.",
            "Горизонт чист. Ветер несёт запах соли и дальних островов. Спокойно — пока.",
            "Пена шипит у ног, тает. Чайка бросила ракушку о камень и улетела.",
            "На отмели среди камней что-то блеснуло. Или показалось. Стоит поискать.",
            "Закат красит берег в рыжий. Самое время поискать, что принесло море."
    );

    private final TideService tideService;
    private final BuildingService buildingService;
    private final PlayerRepository playerRepository;

    @Override
    public PlayerScreen getScreen() {
        return PlayerScreen.ZONE_SHORE_BEACH;
    }

    @Override
    public BotResponse handle(Player player, Island island, String text) {
        return switch (text) {
            case BTN_TIDE -> routeTide(player);
            case BTN_SCAN -> scanBeach(player, island);
            case BTN_BACK -> goBack(player, island);
            default       -> buildBeachScreen(player, tideService);
        };
    }

    // ── Actions ────────────────────────────────────────────────────────────

    private BotResponse routeTide(Player player) {
        player.getState().setCurrentScreen(PlayerScreen.ZONE_SHORE_TIDE);
        playerRepository.save(player);
        return TideGameHandler.buildEntryScreen(player, tideService);
    }

    private BotResponse scanBeach(Player player, Island island) {
        if (!tideService.isBeachReady(player)) {
            return buildBeachScreen(player, tideService);
        }

        TideService.BeachResult result = tideService.scanBeach(player, island);

        RichText rt = new RichText();
        rt.beginBold().add("🏖 Прочёсан пляж").endBold()
          .add("\n\n")
          .add(result.flavorText())
          .add("\n\n");

        if (result.rare()) {
            rt.beginBold().add("+").add(String.valueOf(result.shells())).add(" 🐚").endBold()
              .add(" — редкая находка!");
        } else {
            rt.add("+").beginBold().add(String.valueOf(result.shells())).add(" 🐚").endBold();
        }

        // Use beachCooldownText() so the text stays in sync with the BEACH_COOLDOWN_HOURS constant
        rt.add("\n\nСледующее прочёсывание ").add(tideService.beachCooldownText(player)).add(".");

        // Остаёмся на экране пляжа — followUp показывает обновлённое состояние
        return rt.build().withFollowUp(buildBeachScreen(player, tideService));
    }

    private BotResponse goBack(Player player, Island island) {
        IslandBuilding pier = buildingService.find(island, BuildingType.FISHING_PIER).orElse(null);
        player.getState().setCurrentScreen(PlayerScreen.ZONE_SHORE);
        playerRepository.save(player);
        return ShoreZoneHandler.buildZoneScreen(player, tideService, pier);
    }

    // ── Screen builder ─────────────────────────────────────────────────────

    public static BotResponse buildBeachScreen(Player player, TideService tideService) {
        String flavor = FLAVOR.get(ThreadLocalRandom.current().nextInt(FLAVOR.size()));

        RichText rt = new RichText();
        rt.beginBold().add("🏖 Пляж").endBold()
          .add("\n\n")
          .add(flavor)
          .add("\n\n");

        // Статус прилива
        boolean tideActive = tideService.isActive(player);
        rt.add("🌊 ").bold("Прилив:").add(" ");
        if (tideActive) {
            rt.add("идёт! Не упусти волну.");
        } else {
            rt.add("тихо");
        }
        rt.add("\n");

        // Статус прочёсывания
        rt.add("🏖 ").bold("Пляж:").add(" ");
        if (tideService.isBeachReady(player)) {
            rt.add("здесь что-то есть — ");
            rt.beginBold().add("можно прочесать!").endBold();
        } else {
            rt.add(tideService.beachCooldownText(player));
        }

        return rt.build(buildBeachKeyboard(player, tideService));
    }

    // ── Keyboard ───────────────────────────────────────────────────────────

    private static ReplyKeyboard buildBeachKeyboard(Player player, TideService tideService) {
        KeyboardBuilder kb = KeyboardBuilder.builder();

        // Прилив — только когда активен
        if (tideService.isActive(player)) {
            KeyboardButton tideBtn = new KeyboardButton(BTN_TIDE);
            tideBtn.setStyle("success");
            kb.row(tideBtn);
        }

        // Прочесать пляж — всегда; зелёная когда готово
        KeyboardButton scanBtn = new KeyboardButton(BTN_SCAN);
        if (tideService.isBeachReady(player)) scanBtn.setStyle("success");
        kb.row(scanBtn);

        kb.row(new KeyboardButton(BTN_BACK));
        return kb.build();
    }
}
