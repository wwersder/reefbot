package com.reefbot.service.game.handlers;

import com.reefbot.dto.BotResponse;
import com.reefbot.entity.Island;
import com.reefbot.entity.Player;
import com.reefbot.enums.HuntingSpot;
import com.reefbot.enums.PlayerScreen;
import com.reefbot.repository.PlayerRepository;
import com.reefbot.service.game.GameHandler;
import com.reefbot.service.game.HuntingService;
import com.reefbot.util.KeyboardBuilder;
import com.reefbot.util.RichText;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboard;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

@Component
@RequiredArgsConstructor
public class HuntingMenuHandler implements GameHandler {

    public static final String BTN_EDGE          = "🌿 Опушка";
    public static final String BTN_DEEP_LOCKED   = "🌲 Чаща 🔒 ур. 3";
    public static final String BTN_DEEP_UNLOCKED = "🌲 Чаща";
    public static final String BTN_WILD_LOCKED   = "🐾 Урочище 🔒 ур. 6";
    public static final String BTN_WILD_UNLOCKED = "🐾 Урочище";
    public static final String BTN_START         = "✅ Начать охоту";
    public static final String BTN_BACK          = "◀️ Назад";

    // Spot descriptions shown in spot-detail screen
    private static final Map<HuntingSpot, List<String>> SPOT_DESCS = Map.of(
            HuntingSpot.EDGE, List.of(
                    "Опушка рядом с лагерем. Дичь некрупная, но ходить далеко не нужно.",
                    "Край леса — первый выбор. Быстро, надёжно, без риска.",
                    "Знакомые тропки. Здесь всегда есть что поймать."
            ),
            HuntingSpot.DEEP, List.of(
                    "В глубь чащи. Крупная дичь прячется здесь — надо потерпеть.",
                    "Чаща богаче опушки. Следы уводят всё дальше от лагеря.",
                    "Тёмный густой лес. Охота здесь занимает время, но окупается."
            ),
            HuntingSpot.WILD, List.of(
                    "Урочище — дикое место. Звери здесь крупнее и осторожнее. Нужно терпение.",
                    "Дальнее угодье. Почти нетронутое. Добыча стоит долгого пути.",
                    "Глухомань. Мало кто сюда заходит. Именно поэтому дичь там жирная."
            )
    );

    private static final Map<HuntingSpot, List<String>> SPOT_TIPS = Map.of(
            HuntingSpot.EDGE, List.of(
                    "Подходит, если хочешь быстро пополнить запасы или зайти ненадолго.",
                    "Идеально для коротких сессий — ушёл, вернулся, забрал.",
                    "Лучший выбор если нет времени ждать."
            ),
            HuntingSpot.DEEP, List.of(
                    "Хороший баланс времени и добычи. Мех начинается здесь.",
                    "Оптимально если уходишь на несколько часов.",
                    "Среднее угодье — лучший вариант для регулярной охоты."
            ),
            HuntingSpot.WILD, List.of(
                    "Уходи на ночь — добыча будет ждать утром.",
                    "Идеально если уходишь надолго и хочешь максимум.",
                    "Долгое ожидание, богатая добыча. Для терпеливых."
            )
    );

    private final HuntingService huntingService;
    private final PlayerRepository playerRepository;

    @Override
    public PlayerScreen getScreen() {
        return PlayerScreen.ZONE_FOREST_HUNT_MENU;
    }

    @Override
    public BotResponse handle(Player player, Island island, String text) {
        // Redirect if hunt is already running
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

        int level = player.getForest().getHunterLevel();

        return switch (text) {
            case BTN_EDGE                                     -> selectSpot(HuntingSpot.EDGE, player);
            case BTN_DEEP_LOCKED, BTN_DEEP_UNLOCKED          -> handleDeep(player, level);
            case BTN_WILD_LOCKED, BTN_WILD_UNLOCKED          -> handleWild(player, level);
            case BTN_START                                    -> startHunt(player);
            case BTN_BACK                                     -> goBack(player);
            default                                           -> buildHuntingMenu(player);
        };
    }

    private BotResponse selectSpot(HuntingSpot spot, Player player) {
        player.getForest().setHuntingSpot(spot);
        playerRepository.save(player);
        return buildSpotDetail(spot, player);
    }

    private BotResponse handleDeep(Player player, int level) {
        if (level < HuntingSpot.DEEP.getMinLevel()) {
            return new BotResponse(
                    "🔒 Чаща доступна с уровня охотника 3.\nСейчас у тебя уровень " + level + ".",
                    null, huntingMenuKeyboard(player));
        }
        return selectSpot(HuntingSpot.DEEP, player);
    }

    private BotResponse handleWild(Player player, int level) {
        if (level < HuntingSpot.WILD.getMinLevel()) {
            return new BotResponse(
                    "🔒 Урочище доступно с уровня охотника 6.\nСейчас у тебя уровень " + level + ".",
                    null, huntingMenuKeyboard(player));
        }
        return selectSpot(HuntingSpot.WILD, player);
    }

    private BotResponse startHunt(Player player) {
        HuntingSpot spot = player.getForest().getHuntingSpot();
        if (spot == null) return buildHuntingMenu(player);

        huntingService.startHunt(player, spot);
        player.getState().setCurrentScreen(PlayerScreen.ZONE_FOREST_HUNT_ACTIVE);
        playerRepository.save(player);

        long mins = java.time.Duration.between(
                java.time.LocalDateTime.now(),
                player.getForest().getFinishAt()
        ).toMinutes();
        String timeStr = mins >= 60
                ? (mins / 60) + " ч " + (mins % 60) + " мин"
                : Math.max(1, mins) + " мин";

        String text = "🏹 Охота началась на " + spot.getDisplayName().toLowerCase() + ".\n\n"
                + "Возвращайся через " + timeStr + " — добыча будет ждать.";
        return new BotResponse(text, null, HuntingActiveHandler.activeKeyboard());
    }

    private BotResponse goBack(Player player) {
        // If spot selected but hunt not started — clear spot and return to hub
        if (huntingService.isIdle(player) && player.getForest().getHuntingSpot() != null) {
            player.getForest().setHuntingSpot(null);
            playerRepository.save(player);
            return buildHuntingMenu(player);
        }
        player.getState().setCurrentScreen(PlayerScreen.ZONE_FOREST);
        playerRepository.save(player);
        return ForestZoneHandler.buildZoneScreen(player, huntingService);
    }

    // ── Static builders ──────────────────────────────────────────────────────

    public static BotResponse buildHuntingMenu(Player player) {
        int level = player.getForest().getHunterLevel();
        RichText rt = new RichText();
        rt.bold("🏹 Охота").add("\n")
          .add(HuntingService.levelName(level) + " · Ур. " + level + " · "
               + player.getForest().getHunterXp() + " XP")
          .add("\n\nКуда пойдёшь?");
        return rt.build(huntingMenuKeyboard(player));
    }

    private static ReplyKeyboard huntingMenuKeyboard(Player player) {
        int level = player.getForest().getHunterLevel();
        String deepLabel = level >= HuntingSpot.DEEP.getMinLevel() ? BTN_DEEP_UNLOCKED : BTN_DEEP_LOCKED;
        String wildLabel = level >= HuntingSpot.WILD.getMinLevel() ? BTN_WILD_UNLOCKED : BTN_WILD_LOCKED;
        return KeyboardBuilder.builder()
                .row(BTN_EDGE, deepLabel, wildLabel)
                .row(BTN_BACK)
                .build();
    }

    private static BotResponse buildSpotDetail(HuntingSpot spot, Player player) {
        int level = player.getForest().getHunterLevel();
        int meatBonus = level >= 6 ? 2 : (level >= 2 ? 1 : 0);

        int effectiveMeatMin = spot.getMeatMin() + meatBonus;
        int effectiveMeatMax = spot.getMeatMax() + meatBonus + (level >= 10 ? Math.max(1, (int)(spot.getMeatMax() * 0.3)) : 0);

        RichText rt = new RichText();
        rt.beginBold()
          .add("🏹 Охота · " + spot.getDisplayName())
          .endBold()
          .add("\n\n")
          .add(randomFrom(SPOT_DESCS.get(spot))).add("\n\n")
          .add(randomFrom(SPOT_TIPS.get(spot))).add("\n\n")
          .add("⏱ ").bold("Время:").add(" " + formatMinutes(spot.getBaseMinutes()) + "\n")
          .add("🥩 ").bold("Мясо:").add(" " + effectiveMeatMin + "–" + effectiveMeatMax);

        if (spot.getFurMax() > 0) {
            rt.add("\n🪶 ").bold("Мех:").add(" " + spot.getFurMin() + "–" + spot.getFurMax());
        }
        rt.add("\n⭐ ").bold("Опыт:").add(" +" + spot.getXpReward() + " XP");

        if (level >= 2) {
            rt.add("\n\n✨ Бонусы охотника учтены.");
        }

        return rt.build(KeyboardBuilder.builder().row(BTN_START).row(BTN_BACK).build());
    }

    private static String randomFrom(List<String> list) {
        return list.get(ThreadLocalRandom.current().nextInt(list.size()));
    }

    private static String formatMinutes(int minutes) {
        if (minutes >= 60) return (minutes / 60) + " ч " + (minutes % 60 > 0 ? (minutes % 60) + " мин" : "");
        return minutes + " мин";
    }
}
