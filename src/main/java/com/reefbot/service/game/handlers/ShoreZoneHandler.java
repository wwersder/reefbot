package com.reefbot.service.game.handlers;

import com.reefbot.dto.BotResponse;
import com.reefbot.entity.Island;
import com.reefbot.entity.IslandBuilding;
import com.reefbot.entity.Player;
import com.reefbot.enums.BuildingType;
import com.reefbot.enums.FishingSpot;
import com.reefbot.enums.PlayerScreen;
import com.reefbot.enums.ZoneType;
import com.reefbot.util.ReefEmoji;
import com.reefbot.repository.PlayerRepository;
import com.reefbot.service.game.BuildingService;
import com.reefbot.service.game.FishingService;
import com.reefbot.service.game.GameHandler;
import com.reefbot.service.game.TideService;
import com.reefbot.util.KeyboardBuilder;
import com.reefbot.util.RichText;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboard;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardButton;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Экран зоны «Берег»: флейвор + статусы активностей + вход в рыбалку.
 * Навигация: MAIN → ZONE_SHORE → FISHING_* / ZONE_SHORE_TIDE / ZONE_SHORE_PIER / ZONE_SHORE_BUILDINGS.
 *
 * <p>Кнопки зданий меняются по состоянию:
 * <ul>
 *   <li>Помост не построен / строится → {@link #BTN_CONSTRUCTION} (🏗 Стройка)</li>
 *   <li>Помост готов (ур.1+) → {@link #BTN_PIER} (⚓ Рыбацкий помост), зелёная если есть рыба</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class ShoreZoneHandler implements GameHandler {

    public static final String BTN_FISHING      = "Рыбалка";
    public static final String BTN_TIDE         = "🌊 Прилив!";
    public static final String BTN_BEACH        = "🌊 Прочесать пляж";
    public static final String BTN_CONSTRUCTION = "🏗 Стройка";
    public static final String BTN_PIER         = "⚓ Рыбацкий помост";
    public static final String BTN_BACK         = "◀️ На остров";

    private static final List<String> FLAVOR = List.of(
            "Старый причал поскрипывает на волнах.\nПахнет солью и водорослями.",
            "Волны лениво накатывают на песок.\nЧайки кружат над водой.",
            "Прибой выбросил на берег пучки водорослей.\nГде-то вдалеке плеснула рыба.",
            "Горизонт окрасился в янтарь.\nМелкая рябь бежит по воде.",
            "Морской бриз треплет листья пальм.\nТихо и спокойно.",
            "Песок хрустит под ногами.\nВолна принесла перламутровую ракушку.",
            "Закат красит море в медь.\nСамое время забросить удочку.",
            "В воздухе пахнет дождём — но небо чистое.\nМоре спокойно."
    );

    private final FishingService fishingService;
    private final TideService tideService;
    private final BuildingService buildingService;
    private final PlayerRepository playerRepository;

    @Override
    public PlayerScreen getScreen() {
        return PlayerScreen.ZONE_SHORE;
    }

    @Override
    public BotResponse handle(Player player, Island island, String text) {
        return switch (text) {
            case BTN_FISHING      -> routeFishing(player, island);
            case BTN_TIDE         -> routeTide(player, island);
            case BTN_BEACH        -> scanBeach(player, island);
            case BTN_CONSTRUCTION -> routeConstruction(player, island);
            case BTN_PIER         -> routePier(player, island);
            case BTN_BACK         -> goBack(player, island);
            default               -> {
                IslandBuilding pier = buildingService.find(island, BuildingType.FISHING_PIER).orElse(null);
                yield buildZoneScreen(player, tideService, pier);
            }
        };
    }

    // ── Routing ────────────────────────────────────────────────────────────

    private BotResponse routeFishing(Player player, Island island) {
        if (fishingService.isReady(player)) {
            player.getState().setCurrentScreen(PlayerScreen.FISHING_RESULT);
            playerRepository.save(player);
            return FishingResultHandler.buildResultScreen(player, island, fishingService);
        }
        if (fishingService.isActive(player)) {
            player.getState().setCurrentScreen(PlayerScreen.FISHING_ACTIVE);
            playerRepository.save(player);
            return FishingActiveHandler.buildStatusScreen(player, fishingService,
                    FishingActiveHandler.activeKeyboard());
        }
        player.getState().setCurrentScreen(PlayerScreen.FISHING_MENU);
        playerRepository.save(player);
        return FishingMenuHandler.buildFishingMenu(player);
    }

    private BotResponse routeTide(Player player, Island island) {
        player.getState().setCurrentScreen(PlayerScreen.ZONE_SHORE_TIDE);
        playerRepository.save(player);
        return TideGameHandler.buildEntryScreen(player, tideService);
    }

    /** 🏗 Стройка — хаб для постройки новых зданий (помост ещё не готов). */
    private BotResponse routeConstruction(Player player, Island island) {
        Optional<IslandBuilding> pierOpt = buildingService.find(island, BuildingType.FISHING_PIER);
        // Если помост уже работает — редирект на экран помоста
        if (pierOpt.isPresent() && pierOpt.get().getLevel() > 0) {
            return routePierDirect(player, island, pierOpt.get());
        }
        player.getState().setCurrentScreen(PlayerScreen.ZONE_SHORE_BUILDINGS);
        playerRepository.save(player);
        return ShoreBuildingsHandler.buildConstructionScreen(island, pierOpt.orElse(null));
    }

    /** ⚓ Рыбацкий помост — погружающий экран готового здания. */
    private BotResponse routePier(Player player, Island island) {
        Optional<IslandBuilding> pierOpt = buildingService.find(island, BuildingType.FISHING_PIER);
        if (pierOpt.isPresent() && buildingService.isConstructionReady(pierOpt.get())) {
            pierOpt = Optional.of(buildingService.finalize(pierOpt.get()));
        }
        IslandBuilding pier = pierOpt.orElse(null);
        if (pier == null || pier.getLevel() == 0) {
            // Помост не готов — в стройку
            return routeConstruction(player, island);
        }
        return routePierDirect(player, island, pier);
    }

    private BotResponse routePierDirect(Player player, Island island, IslandBuilding pier) {
        player.getState().setCurrentScreen(PlayerScreen.ZONE_SHORE_PIER);
        playerRepository.save(player);
        return ShorePierHandler.buildPierScreen(island, pier);
    }

    private BotResponse scanBeach(Player player, Island island) {
        if (!tideService.isBeachReady(player)) {
            IslandBuilding pier = buildingService.find(island, BuildingType.FISHING_PIER).orElse(null);
            return buildZoneScreen(player, tideService, pier);
        }
        TideService.BeachResult result = tideService.scanBeach(player, island);

        RichText rt = new RichText();
        rt.beginBold().add("🌊 Прочёсан пляж").endBold()
          .add("\n\n")
          .add(result.flavorText())
          .add("\n\n");

        if (result.rare()) {
            rt.beginBold().add("+").add(String.valueOf(result.shells())).add(" 🐚").endBold()
              .add(" — редкая находка!");
        } else {
            rt.add("+").beginBold().add(String.valueOf(result.shells())).add(" 🐚").endBold();
        }

        rt.add("\n\nСледующий раз через 4 часа.");

        IslandBuilding pier = buildingService.find(island, BuildingType.FISHING_PIER).orElse(null);
        return rt.build().withFollowUp(buildZoneScreen(player, tideService, pier));
    }

    private BotResponse goBack(Player player, Island island) {
        player.getState().setCurrentScreen(PlayerScreen.MAIN);
        playerRepository.save(player);
        return MainMenuHandler.showMainMenu(player, island);
    }

    // ── Static helpers (другие handlers «приземляют» игрока сюда) ───────────

    /** Экран зоны без TideService (транзитные вызовы из рыбалки). */
    public static BotResponse buildZoneScreen(Player player) {
        return buildZoneScreen(player, null);
    }

    /** Полный экран зоны с приливом (pier не известен). */
    public static BotResponse buildZoneScreen(Player player, TideService tideService) {
        return buildZoneScreen(player, tideService, null);
    }

    /**
     * Полный экран зоны.
     * Всегда используй эту перегрузку если pier известен — иначе он не покажет статус в дайджесте.
     */
    public static BotResponse buildZoneScreen(Player player, TideService tideService, IslandBuilding pier) {
        String flavor = FLAVOR.get(ThreadLocalRandom.current().nextInt(FLAVOR.size()));

        RichText rt = new RichText();
        rt.beginBold().emoji(ReefEmoji.SHORE).add(" Берег").endBold()
          .add("\n\n")
          .add(flavor)
          .add("\n\n");
        appendFishingStatus(rt, player);

        if (tideService != null) {
            rt.add("\n");
            appendTideStatus(rt, player, tideService);
            rt.add("\n");
            appendBeachStatus(rt, player, tideService);
        }

        if (pier != null && pier.getLevel() > 0) {
            rt.add("\n");
            appendPierStatus(rt, pier);
        }

        return rt.build(ZoneType.SHORE.getBannerPath(), keyboard(player, tideService, pier));
    }

    // ── Status lines ───────────────────────────────────────────────────────

    private static void appendFishingStatus(RichText rt, Player player) {
        LocalDateTime finishAt = player.getFishing().getFishingFinishAt();
        rt.emoji(ReefEmoji.FISHING).add(" ").bold("Рыбалка:").add(" ");
        if (finishAt == null) {
            rt.add("свободна");
        } else if (!LocalDateTime.now().isBefore(finishAt)) {
            FishingSpot spot = player.getFishing().getFishingSpot();
            if (spot != null) {
                rt.emoji(FishingMenuHandler.spotEmojiDef(spot)).add(" " + FishingMenuHandler.spotName(spot) + " — ");
            }
            rt.add("есть улов! ").emoji(ReefEmoji.CHECK);
        } else {
            rt.add("⏳ ещё " + remainingText(finishAt));
        }
    }

    private static void appendTideStatus(RichText rt, Player player, TideService tideService) {
        rt.add("🌊 ").bold("Прилив:").add(" ");
        if (tideService.isActive(player)) {
            rt.add("идёт! Открыто 40 минут.");
        } else {
            rt.add("тихо");
        }
    }

    private static void appendBeachStatus(RichText rt, Player player, TideService tideService) {
        rt.add("🏖 ").bold("Пляж:").add(" ");
        if (tideService.isBeachReady(player)) {
            rt.add("можно прочесать!");
        } else {
            rt.add(tideService.beachCooldownText(player));
        }
    }

    private static void appendPierStatus(RichText rt, IslandBuilding pier) {
        rt.add("⚓ ").bold("Помост:").add(" ");
        if (pier.getBuildFinishAt() != null && LocalDateTime.now().isBefore(pier.getBuildFinishAt())) {
            // Апгрейд в процессе
            long mins = Math.max(1, Duration.between(LocalDateTime.now(), pier.getBuildFinishAt()).toMinutes());
            rt.add("ур." + pier.getLevel() + " — ⏳ улучшается (" + mins + " мин)");
        } else {
            // Работает нормально
            int acc = ShorePierHandler.calcAccumulated(pier);
            int cap = BuildingType.FISHING_PIER.capAt(pier.getLevel());
            if (acc >= cap) {
                rt.add("ур." + pier.getLevel() + " — ").bold("полон! " + acc + " 🐟");
            } else {
                rt.add("ур." + pier.getLevel() + " — " + acc + "/" + cap + " 🐟");
            }
        }
    }

    // ── Keyboard ───────────────────────────────────────────────────────────

    private static String remainingText(LocalDateTime finishAt) {
        long totalSeconds = Math.max(0, Duration.between(LocalDateTime.now(), finishAt).getSeconds());
        long minutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;
        if (minutes > 0) return minutes + " мин " + seconds + " сек";
        return seconds + " сек";
    }

    private static ReplyKeyboard keyboard(Player player, TideService tideService, IslandBuilding pier) {
        // Рыбалка
        KeyboardButton fishingBtn = KeyboardBuilder.btn(BTN_FISHING, ReefEmoji.FISHING.id());
        LocalDateTime finishAt = player.getFishing().getFishingFinishAt();
        if (finishAt != null && !LocalDateTime.now().isBefore(finishAt)) {
            fishingBtn.setStyle("success");
        }
        KeyboardBuilder kb = KeyboardBuilder.builder().row(fishingBtn);

        // Прилив
        if (tideService != null) {
            if (tideService.isActive(player)) {
                KeyboardButton tideBtn = new KeyboardButton(BTN_TIDE);
                tideBtn.setStyle("success");
                kb.row(tideBtn);
            }

            // Прочесать пляж — всегда, зелёная когда готово
            KeyboardButton beachBtn = new KeyboardButton(BTN_BEACH);
            if (tideService.isBeachReady(player)) beachBtn.setStyle("success");
            kb.row(beachBtn);
        }

        // Здания: если помост работает (level > 0) — кнопка помоста,
        // иначе — кнопка «Стройка» для начала строительства
        boolean pierReady = pier != null && pier.getLevel() > 0;
        if (pierReady) {
            KeyboardButton pierBtn = new KeyboardButton(BTN_PIER);
            int acc = ShorePierHandler.calcAccumulated(pier);
            if (acc > 0) pierBtn.setStyle("success");
            kb.row(pierBtn);
        } else {
            // Показываем кнопку стройки пока помост не достиг уровня 1
            KeyboardButton constructionBtn = new KeyboardButton(BTN_CONSTRUCTION);
            kb.row(constructionBtn);
        }

        kb.row(new KeyboardButton(BTN_BACK));
        return kb.build();
    }
}
