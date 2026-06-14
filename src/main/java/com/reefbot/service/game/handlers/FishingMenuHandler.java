package com.reefbot.service.game.handlers;

import com.reefbot.bot.handlers.LevelsCallbackHandler;
import com.reefbot.dto.BotResponse;
import com.reefbot.entity.Island;
import com.reefbot.entity.Player;
import com.reefbot.enums.FishingSpot;
import com.reefbot.enums.PlayerScreen;
import com.reefbot.entity.IslandBuilding;
import com.reefbot.enums.BuildingType;
import com.reefbot.repository.PlayerRepository;
import com.reefbot.service.game.BuildingService;
import com.reefbot.service.game.FishingService;
import com.reefbot.service.game.GameHandler;
import com.reefbot.util.EmojiUtil;
import com.reefbot.util.KeyboardBuilder;
import com.reefbot.util.ReefEmoji;
import com.reefbot.util.RichText;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

@Component
@RequiredArgsConstructor
public class FishingMenuHandler implements GameHandler {

    public static final String BTN_SHORE             = "У берега";
    public static final String BTN_REEF              = "У рифа";
    public static final String BTN_OPEN_SEA_LOCKED   = "В море 🔒 ур. 3";
    public static final String BTN_OPEN_SEA_UNLOCKED = "В море";
    public static final String BTN_BACK              = "◀️ Назад";
    public static final String BTN_LEVELS            = "Уровни";
    public static final String BTN_CAST              = "✅ Закинуть удочку";
    public static final String BTN_BONUSES           = "✨ Бонусы";
    public static final String BTN_INVENTORY         = "🎒 Рюкзак";

    private final FishingService fishingService;
    private final PlayerRepository playerRepository;
    private final FishingInventoryHandler fishingInventoryHandler;
    private final com.reefbot.service.game.TideService tideService;
    private final BuildingService buildingService;

    @Override
    public PlayerScreen getScreen() {
        return PlayerScreen.FISHING_MENU;
    }

    @Override
    public BotResponse handle(Player player, Island island, String text) {
        // Защита: если рыбалка активна/готова, а игрок оказался в меню выбора —
        // перенаправляем. Это предотвращает абуз через нестандартную навигацию.
        if (fishingService.isActive(player)) {
            player.getState().setCurrentScreen(PlayerScreen.FISHING_ACTIVE);
            playerRepository.save(player);
            return FishingActiveHandler.buildStatusScreen(player, fishingService,
                    FishingActiveHandler.activeKeyboard());
        }
        if (fishingService.isReady(player)) {
            player.getState().setCurrentScreen(PlayerScreen.FISHING_RESULT);
            playerRepository.save(player);
            return FishingResultHandler.buildResultScreen(player, island, fishingService);
        }

        return switch (text) {
            case BTN_SHORE                                   -> spotDetail(FishingSpot.SHORE, player);
            case BTN_REEF                                    -> spotDetail(FishingSpot.REEF, player);
            case BTN_OPEN_SEA_LOCKED, BTN_OPEN_SEA_UNLOCKED -> handleOpenSea(player);
            case BTN_BACK                                    -> goBack(player, island);
            case BTN_CAST                                    -> castLine(player, island);
            case BTN_BONUSES                                 -> showBonuses(player);
            case BTN_LEVELS                                  -> LevelsCallbackHandler.buildInitialMessage(player);
            case BTN_INVENTORY                               -> openInventory(player);
            default                                          -> buildFishingMenu(player);
        };
    }

    private BotResponse spotDetail(FishingSpot spot, Player player) {
        player.getFishing().setFishingSpot(spot);
        playerRepository.save(player);
        return buildSpotDetail(spot, player);
    }

    private BotResponse handleOpenSea(Player player) {
        if (player.getFishing().getFishingLevel() < FishingSpot.OPEN_SEA.getMinLevel()) {
            return new BotResponse(
                    "🔒 Открытое море доступно с уровня рыбака 3.\nСейчас у тебя уровень "
                            + player.getFishing().getFishingLevel() + ".",
                    null,
                    buildFishingKeyboard(player)
            );
        }
        return spotDetail(FishingSpot.OPEN_SEA, player);
    }

    private BotResponse castLine(Player player, Island island) {
        FishingSpot spot = player.getFishing().getFishingSpot();
        if (spot == null) return buildFishingMenu(player);

        fishingService.startFishing(player, spot);

        // 🫙 Tide vial instant: fishing done immediately — skip active screen
        if (fishingService.isReady(player)) {
            player.getState().setCurrentScreen(PlayerScreen.FISHING_RESULT);
            playerRepository.save(player);
            return FishingResultHandler.buildResultScreen(player, island, fishingService);
        }

        player.getState().setCurrentScreen(PlayerScreen.FISHING_ACTIVE);
        playerRepository.save(player);

        String text = String.format("""
                ⏳ Удочка заброшена %s

                Возвращайся через %d мин — улов будет ждать.
                """, spot.getDisplayName().toLowerCase(), spot.getDurationMinutes());

        return new BotResponse(text, null, FishingActiveHandler.activeKeyboard());
    }

    private BotResponse showBonuses(Player player) {
        player.getState().setCurrentScreen(PlayerScreen.FISHING_BONUSES);
        playerRepository.save(player);
        return FishingBonusesHandler.buildBonusesScreen(player);
    }

    private BotResponse openInventory(Player player) {
        player.getState().setCurrentScreen(PlayerScreen.FISHING_INVENTORY);
        playerRepository.save(player);
        return fishingInventoryHandler.buildScreen(player);
    }

    private BotResponse goBack(Player player, Island island) {
        // Если рыбалка идёт — нельзя трогать spot, просто уходим на берег.
        // Spot будет очищен только в collectFish() после получения улова.
        if (fishingService.isActive(player) || fishingService.isReady(player)) {
            player.getState().setCurrentScreen(PlayerScreen.ZONE_SHORE);
            playerRepository.save(player);
            IslandBuilding pier = buildingService.find(island, BuildingType.FISHING_PIER).orElse(null);
            return ShoreZoneHandler.buildZoneScreen(player, tideService, pier);
        }
        // Spot выбран, но удочка ещё не заброшена — вернуть к выбору места
        if (player.getFishing().getFishingSpot() != null) {
            player.getFishing().setFishingSpot(null);
            playerRepository.save(player);
            return buildFishingMenu(player);
        }
        player.getState().setCurrentScreen(PlayerScreen.ZONE_SHORE);
        playerRepository.save(player);
        IslandBuilding pier = buildingService.find(island, BuildingType.FISHING_PIER).orElse(null);
        return ShoreZoneHandler.buildZoneScreen(player, tideService, pier);
    }

    // ── Static helpers ───────────────────────────────────────────────────────

    public static BotResponse buildFishingMenu(Player player) {
        int level = player.getFishing().getFishingLevel();

        RichText rt = new RichText();
        rt.emoji(ReefEmoji.FISHING).add(" ").bold("Рыбалка")
          .add("\n")
          .add(FishingService.levelName(level) + " · Ур. " + level + " · ")
          .emoji(ReefEmoji.STAR).add(" " + player.getFishing().getFishingXp() + " XP");

        String effects = activeEffectsLine(player);
        if (!effects.isEmpty()) {
            rt.add("\n⚡ Активно: ").add(effects);
        }

        rt.add("\n\nКуда забросить удочку?");

        return rt.build(buildFishingKeyboard(player));
    }

    /** Строка активных эффектов для отображения в меню, напр. "📜 🪱 🪝". */
    public static String activeEffectsLine(Player player) {
        var f = player.getFishing();
        StringBuilder sb = new StringBuilder();
        if (Boolean.TRUE.equals(f.getEffectSpeedCast()))   sb.append("📜 ");
        if (Boolean.TRUE.equals(f.getEffectYieldBonus()))  sb.append("🪱 ");
        if (Boolean.TRUE.equals(f.getEffectXpBonus()))     sb.append("🪝 ");
        if (Boolean.TRUE.equals(f.getEffectInstantNext())) sb.append("🫙 ");
        return sb.toString().trim();
    }

    private static org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboard buildFishingKeyboard(Player player) {
        boolean openSeaLocked = player.getFishing().getFishingLevel() < FishingSpot.OPEN_SEA.getMinLevel();
        String openSeaLabel = openSeaLocked ? BTN_OPEN_SEA_LOCKED : BTN_OPEN_SEA_UNLOCKED;

        return KeyboardBuilder.builder()
                .row(
                    KeyboardBuilder.btn(BTN_SHORE, ReefEmoji.SHORE.id()),
                    KeyboardBuilder.btn(BTN_REEF,  ReefEmoji.REEF.id()),
                    KeyboardBuilder.btn(openSeaLabel, ReefEmoji.OPEN_SEA.id())
                )
                .row(
                    KeyboardBuilder.btn(BTN_LEVELS, ReefEmoji.LEVELS.id()),
                    new org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardButton(BTN_INVENTORY),
                    new org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardButton(BTN_BACK)
                )
                .build();
    }

    public static BotResponse buildSpotDetail(FishingSpot spot, Player player) {
        boolean hasBonuses = player.getFishing().getFishingLevel() >= 2;

        RichText rt = new RichText();

        // Заголовок: 🎣 Рыбалка 🌴 У берега (весь bold, эмодзи — кастомные)
        rt.beginBold()
          .emoji(ReefEmoji.FISHING).add(" Рыбалка ").emoji(spotEmojiDef(spot)).add(" " + spotName(spot))
          .endBold()
          .add("\n\n")
          .add(spotRandomDesc(spot)).add("\n\n")
          .add(spotRandomSub(spot)).add("\n\n")
          .emoji(ReefEmoji.TIMER).add(" ").bold("Время:").add(" " + spot.getDurationMinutes() + " мин\n")
          .emoji(ReefEmoji.FISH).add(" ").bold("Улов:").add(" " + spot.getMinFish() + "–" + spot.getMaxFish() + " рыбы\n")
          .emoji(ReefEmoji.STAR).add(" ").bold("Опыт:").add(" +" + spot.getXpReward() + " XP");

        if (spot.getBonusResource() != null) {
            rt.add("\n" + bonusEmoji(spot) + " ").bold("Шанс " + bonusName(spot) + ":").add(" " + spot.getBonusChance() + "%");
        }
        if (hasBonuses) {
            rt.add("\n\n").emoji(ReefEmoji.SPARKLES).add(" У вас активны бонусы рыбака");
        }

        KeyboardBuilder kb = KeyboardBuilder.builder().row(BTN_CAST);
        if (hasBonuses) {
            kb.row(BTN_BONUSES, BTN_BACK);
        } else {
            kb.row(BTN_BACK);
        }
        return rt.build(kb.build());
    }

    /** EmojiUtil.Def места рыбалки — для кастом-эмодзи в тексте. */
    public static EmojiUtil.Def spotEmojiDef(FishingSpot spot) {
        return switch (spot) {
            case SHORE    -> ReefEmoji.SHORE;
            case REEF     -> ReefEmoji.REEF;
            case OPEN_SEA -> ReefEmoji.OPEN_SEA;
        };
    }

    /** Название места без эмодзи (для составных строк). */
    public static String spotName(FishingSpot spot) {
        return switch (spot) {
            case SHORE    -> "У берега";
            case REEF     -> "У рифа";
            case OPEN_SEA -> "В открытом море";
        };
    }

    private static final Map<FishingSpot, List<String>> SPOT_DESCS = Map.of(
            FishingSpot.SHORE, List.of(
                    "Спокойное место у кромки воды. Быстро, но улов скромный.",
                    "Мелкая вода, видно каждый камень. Рыба мелкая, но клюёт охотно.",
                    "Тихое местечко. Удочку видно до самого дна.",
                    "Вода прозрачная, рыбы немного — но поймать можно быстро.",
                    "Прибрежная полоса — первый выбор каждого рыбака."
            ),
            FishingSpot.REEF, List.of(
                    "Рыба здесь крупнее — прячется под камнями. Стоит подождать.",
                    "Коралловые рифы кишат живностью. Придётся немного подождать — оно того стоит.",
                    "Тёплая вода у рифа привлекает хорошую рыбу. Терпение вознаграждается.",
                    "Пёстрые рыбы снуют между кораллами. Улов богаче берега.",
                    "Под рифом прячется нечто ценное. Нужна выдержка."
            ),
            FishingSpot.OPEN_SEA, List.of(
                    "Далеко от берега, глубокая вода. Богатый улов, но нужно терпение.",
                    "Открытое море таит крупную добычу. Жди — и не пожалеешь.",
                    "Синяя даль, берега не видно. Только ты, удочка и горизонт.",
                    "Глубокая вода скрывает богатый улов. Придётся набраться терпения.",
                    "Здесь водится настоящая рыба. Ждать долго, но результат впечатляет."
            )
    );

    private static final Map<FishingSpot, List<String>> SPOT_SUBS = Map.of(
            FishingSpot.SHORE, List.of(
                    "Подходит если хочешь быстро попробовать, нужны ресурсы срочно, или у тебя мало времени.",
                    "Лучший выбор если возвращаешься ненадолго или хочешь быстро поднять XP.",
                    "Идеально для коротких сессий — забросил, вернулся через минуту."
            ),
            FishingSpot.REEF, List.of(
                    "Лучший выбор для ежедневной игры — хороший баланс улова и времени.",
                    "Оптимальный вариант если есть 10 минут — улов в разы богаче берега.",
                    "Здесь стоит задержаться — рыба покрупнее, шанс ракушек выше."
            ),
            FishingSpot.OPEN_SEA, List.of(
                    "Идеально: забросил, занялся другим, вернулся — и вытащил большой улов.",
                    "Подходит если уходишь надолго — пусть удочка работает пока тебя нет.",
                    "Лучший выбор на ночь или когда уходишь на несколько часов."
            )
    );

    private static String spotRandomDesc(FishingSpot spot) {
        List<String> list = SPOT_DESCS.get(spot);
        return list.get(ThreadLocalRandom.current().nextInt(list.size()));
    }

    private static String spotRandomSub(FishingSpot spot) {
        List<String> list = SPOT_SUBS.get(spot);
        return list.get(ThreadLocalRandom.current().nextInt(list.size()));
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
