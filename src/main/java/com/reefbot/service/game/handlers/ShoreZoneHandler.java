package com.reefbot.service.game.handlers;

import com.reefbot.dto.BotResponse;
import com.reefbot.entity.Island;
import com.reefbot.entity.Player;
import com.reefbot.enums.FishingSpot;
import com.reefbot.enums.PlayerScreen;
import com.reefbot.enums.ZoneType;
import com.reefbot.util.ReefEmoji;
import com.reefbot.repository.PlayerRepository;
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
import java.util.concurrent.ThreadLocalRandom;

/**
 * Экран зоны «Берег»: флейвор + статусы активностей + вход в рыбалку.
 * Навигация: MAIN → ZONE_SHORE → FISHING_*.
 */
@Component
@RequiredArgsConstructor
public class ShoreZoneHandler implements GameHandler {

    public static final String BTN_FISHING = "Рыбалка";
    public static final String BTN_TIDE    = "🌊 Прилив!";
    public static final String BTN_BACK    = "◀️ На остров";

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
    private final PlayerRepository playerRepository;

    @Override
    public PlayerScreen getScreen() {
        return PlayerScreen.ZONE_SHORE;
    }

    @Override
    public BotResponse handle(Player player, Island island, String text) {
        return switch (text) {
            case BTN_FISHING -> routeFishing(player, island);
            case BTN_TIDE    -> routeTide(player, island);
            case BTN_BACK    -> goBack(player, island);
            default          -> buildZoneScreen(player, tideService);
        };
    }

    /** Вход в рыбалку в зависимости от её состояния (перенесено из MainMenuHandler). */
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

    /** Полный экран зоны с приливом (если tideService != null). */
    public static BotResponse buildZoneScreen(Player player, TideService tideService) {
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
        }

        return rt.build(ZoneType.SHORE.getBannerPath(), keyboard(player, tideService));
    }

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

    private static String remainingText(LocalDateTime finishAt) {
        long totalSeconds = Math.max(0, Duration.between(LocalDateTime.now(), finishAt).getSeconds());
        long minutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;
        if (minutes > 0) return minutes + " мин " + seconds + " сек";
        return seconds + " сек";
    }

    private static ReplyKeyboard keyboard(Player player, TideService tideService) {
        KeyboardButton fishingBtn = KeyboardBuilder.btn(BTN_FISHING, ReefEmoji.FISHING.id());
        LocalDateTime finishAt = player.getFishing().getFishingFinishAt();
        if (finishAt != null && !LocalDateTime.now().isBefore(finishAt)) {
            fishingBtn.setStyle("success");
        }

        KeyboardBuilder kb = KeyboardBuilder.builder().row(fishingBtn);

        if (tideService != null && tideService.isActive(player)) {
            KeyboardButton tideBtn = new KeyboardButton(BTN_TIDE);
            tideBtn.setStyle("success");
            kb.row(tideBtn);
        }

        kb.row(new KeyboardButton(BTN_BACK));
        return kb.build();
    }
}
