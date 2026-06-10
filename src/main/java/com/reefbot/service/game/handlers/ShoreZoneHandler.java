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
import com.reefbot.util.KeyboardBuilder;
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

    public static final String BTN_FISHING = "🎣 Рыбалка";
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
    private final PlayerRepository playerRepository;

    @Override
    public PlayerScreen getScreen() {
        return PlayerScreen.ZONE_SHORE;
    }

    @Override
    public BotResponse handle(Player player, Island island, String text) {
        return switch (text) {
            case BTN_FISHING -> routeFishing(player, island);
            case BTN_BACK    -> goBack(player, island);
            default          -> buildZoneScreen(player);
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

    private BotResponse goBack(Player player, Island island) {
        player.getState().setCurrentScreen(PlayerScreen.MAIN);
        playerRepository.save(player);
        return MainMenuHandler.showMainMenu(player, island);
    }

    // ── Static helpers (другие handlers «приземляют» игрока сюда) ───────────

    /** Экран зоны: флейвор + статусы. Вызывающий сам ставит currentScreen = ZONE_SHORE. */
    public static BotResponse buildZoneScreen(Player player) {
        String flavor = FLAVOR.get(ThreadLocalRandom.current().nextInt(FLAVOR.size()));

        String text = "<b>" + ReefEmoji.SHORE_TEXT + " Берег</b>\n\n"
                + flavor + "\n\n"
                + fishingStatusLine(player);

        return new BotResponse(text, ZoneType.SHORE.getBannerPath(), keyboard(player), null, null, "HTML");
    }

    private static String fishingStatusLine(Player player) {
        LocalDateTime finishAt = player.getFishing().getFishingFinishAt();
        if (finishAt == null) {
            return "🎣 <b>Рыбалка:</b> свободна";
        }
        if (!LocalDateTime.now().isBefore(finishAt)) {
            FishingSpot spot = player.getFishing().getFishingSpot();
            String spotPart = spot != null ? FishingMenuHandler.spotDisplayHtml(spot) + " — " : "";
            return "🎣 <b>Рыбалка:</b> " + spotPart + "есть улов! ✅";
        }
        return "🎣 <b>Рыбалка:</b> ⏳ ещё " + remainingText(finishAt);
    }

    private static String remainingText(LocalDateTime finishAt) {
        long totalSeconds = Math.max(0, Duration.between(LocalDateTime.now(), finishAt).getSeconds());
        long minutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;
        if (minutes > 0) return minutes + " мин " + seconds + " сек";
        return seconds + " сек";
    }

    private static ReplyKeyboard keyboard(Player player) {
        KeyboardButton fishingBtn = new KeyboardButton(BTN_FISHING);
        LocalDateTime finishAt = player.getFishing().getFishingFinishAt();
        if (finishAt != null && !LocalDateTime.now().isBefore(finishAt)) {
            fishingBtn.setStyle("success");
        }
        return KeyboardBuilder.builder()
                .row(fishingBtn)
                .row(new KeyboardButton(BTN_BACK))
                .build();
    }
}
