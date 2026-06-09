package com.reefbot.service.game.handlers;

import com.reefbot.dto.BotResponse;
import com.reefbot.entity.Island;
import com.reefbot.entity.Player;
import com.reefbot.enums.FishingSpot;
import com.reefbot.enums.PlayerScreen;
import com.reefbot.repository.PlayerRepository;
import com.reefbot.service.game.FishingService;
import com.reefbot.service.game.GameHandler;
import com.reefbot.util.KeyboardBuilder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class FishingMenuHandler implements GameHandler {

    public static final String BTN_SHORE              = "🏖 У берега — 1 мин";
    public static final String BTN_REEF               = "🪨 У рифа — 10 мин";
    public static final String BTN_OPEN_SEA_LOCKED    = "🌊 В открытом море — 30 мин 🔒 ур. 3";
    public static final String BTN_OPEN_SEA_UNLOCKED  = "🌊 В открытом море — 30 мин";
    public static final String BTN_BACK               = "◀️ Назад";
    public static final String BTN_CAST               = "✅ Закинуть удочку";

    private final FishingService fishingService;
    private final PlayerRepository playerRepository;

    @Override
    public PlayerScreen getScreen() {
        return PlayerScreen.FISHING_MENU;
    }

    @Override
    public BotResponse handle(Player player, Island island, String text) {
        return switch (text) {
            case BTN_SHORE                             -> spotDetail(FishingSpot.SHORE, player);
            case BTN_REEF                              -> spotDetail(FishingSpot.REEF, player);
            case BTN_OPEN_SEA_LOCKED, BTN_OPEN_SEA_UNLOCKED -> handleOpenSea(player);
            case BTN_BACK                              -> goBack(player, island);
            case BTN_CAST                              -> castLine(player);
            default                                    -> buildFishingMenu(player);
        };
    }

    private BotResponse spotDetail(FishingSpot spot, Player player) {
        // Temporarily encode chosen spot in fishingSpot field (not yet started)
        player.setFishingSpot(spot);
        playerRepository.save(player);
        return buildSpotDetail(spot, player);
    }

    private BotResponse handleOpenSea(Player player) {
        if (player.getFishingLevel() < FishingSpot.OPEN_SEA.getMinLevel()) {
            return new BotResponse(
                    "🔒 Открытое море доступно с уровня рыбака 3.\nСейчас у тебя уровень " + player.getFishingLevel() + ".",
                    null,
                    buildFishingKeyboard(player)
            );
        }
        return spotDetail(FishingSpot.OPEN_SEA, player);
    }

    private BotResponse castLine(Player player) {
        FishingSpot spot = player.getFishingSpot();
        if (spot == null) return buildFishingMenu(player);

        fishingService.startFishing(player, spot); // saves player with finishAt
        player.setCurrentScreen(PlayerScreen.FISHING_ACTIVE);
        playerRepository.save(player);

        String text = String.format("""
                ⏳ Удочка заброшена %s

                Возвращайся через %d мин — улов будет ждать.
                """, spot.getDisplayName().toLowerCase(), spot.getDurationMinutes());

        return new BotResponse(text, null, FishingActiveHandler.activeKeyboard());
    }

    private BotResponse goBack(Player player, Island island) {
        if (player.getFishingSpot() != null) {
            // From spot detail → back to spot selection
            player.setFishingSpot(null);
            playerRepository.save(player);
            return buildFishingMenu(player);
        }
        // From spot selection → back to main menu
        player.setCurrentScreen(PlayerScreen.MAIN);
        playerRepository.save(player);
        return MainMenuHandler.showMainMenu(player, island);
    }

    // ── Static helpers ──────────────────────────────────────────────────────

    public static BotResponse buildFishingMenu(Player player) {
        String text = String.format("""
                🎣 Рыбалка
                Уровень рыбака: %d · ⭐ %d XP

                Куда забросить удочку?
                """, player.getFishingLevel(), player.getFishingXp());

        return new BotResponse(text, null, buildFishingKeyboard(player));
    }

    private static org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboard buildFishingKeyboard(Player player) {
        boolean openSeaLocked = player.getFishingLevel() < FishingSpot.OPEN_SEA.getMinLevel();
        String openSeaBtn = openSeaLocked ? BTN_OPEN_SEA_LOCKED : BTN_OPEN_SEA_UNLOCKED;

        return KeyboardBuilder.builder()
                .row(BTN_SHORE, BTN_REEF, openSeaBtn)
                .row(BTN_BACK)
                .build();
    }

    private static BotResponse buildSpotDetail(FishingSpot spot, Player player) {
        String bonusLine = spot.getBonusResource() != null
                ? String.format("\n%s Шанс %s: %d%%",
                    bonusEmoji(spot), bonusName(spot), spot.getBonusChance())
                : "";

        String text = String.format("""
                %s Рыбалка %s

                %s

                ⏱ Время: %d мин
                🐟 Улов: %d–%d рыбы
                ⭐ Опыт: +%d XP%s
                """,
                spot.getDisplayName().split(" ")[0],
                spot.getDisplayName().substring(spot.getDisplayName().indexOf(' ')),
                spotDescription(spot),
                spot.getDurationMinutes(),
                spot.getMinFish(), spot.getMaxFish(),
                spot.getXpReward(),
                bonusLine);

        return new BotResponse(text, null,
                KeyboardBuilder.builder()
                        .row(BTN_CAST)
                        .row(BTN_BACK)
                        .build());
    }

    private static String spotDescription(FishingSpot spot) {
        return switch (spot) {
            case SHORE    -> "Спокойное место у кромки воды.\nБыстро, но улов скромный.\n\nПодходит если хочешь быстро попробовать\nили у тебя мало времени.";
            case REEF     -> "Рыба здесь крупнее — прячется\nпод камнями. Стоит подождать.\n\nЛучший выбор для ежедневной игры —\nхороший баланс улова и времени.";
            case OPEN_SEA -> "Далеко от берега, глубокая вода.\nБогатый улов, но нужно терпение.\n\nИдеально: забросил, занялся другим,\nвернулся — и вытащил большой улов.";
        };
    }

    private static String bonusEmoji(FishingSpot spot) {
        return switch (spot.getBonusResource()) {
            case SHELLS -> "🐚";
            case CORAL  -> "🪸";
            default     -> "✨";
        };
    }

    private static String bonusName(FishingSpot spot) {
        return switch (spot.getBonusResource()) {
            case SHELLS -> "ракушки";
            case CORAL  -> "кораллов";
            default     -> spot.getBonusResource().name();
        };
    }
}
