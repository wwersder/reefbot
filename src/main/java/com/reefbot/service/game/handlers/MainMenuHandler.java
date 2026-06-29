package com.reefbot.service.game.handlers;

import com.reefbot.dto.BotResponse;
import com.reefbot.entity.Island;
import com.reefbot.entity.Player;
import com.reefbot.enums.PlayerScreen;
import com.reefbot.enums.ZoneType;
import com.reefbot.entity.IslandBuilding;
import com.reefbot.enums.BuildingType;
import com.reefbot.repository.PlayerRepository;
import com.reefbot.service.game.BuildingService;
import com.reefbot.service.game.DailyBonusService;
import com.reefbot.service.game.ForestService;
import com.reefbot.service.game.GameHandler;
import com.reefbot.service.game.MineService;
import com.reefbot.service.game.TideService;
import com.reefbot.util.EmojiUtil;
import com.reefbot.util.Fmt;
import com.reefbot.util.KeyboardBuilder;
import com.reefbot.util.RichText;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboard;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardButton;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

@Component
@RequiredArgsConstructor
public class MainMenuHandler implements GameHandler {

    public static final String BTN_ISLAND = "🏝 Мой остров";
    public static final String BTN_INV    = "🎒 Инвентарь";

    // ── Стадии острова ────────────────────────────────────────────────────────
    private static final EmojiUtil.Def STAGE_WILD   = EmojiUtil.e("🌿", "5449850741667668411");
    private static final EmojiUtil.Def STAGE_YOUNG  = EmojiUtil.e("🏡", "5411519258062516765");
    private static final EmojiUtil.Def STAGE_LIVELY = EmojiUtil.e("🐬", "5805338278450175585");
    private static final EmojiUtil.Def STAGE_BLOOM  = EmojiUtil.e("🍀", "5807669483619226764");

    private final PlayerRepository playerRepository;
    private final TideService tideService;
    private final BuildingService buildingService;
    private final ForestService forestService;
    private final MineService mineService;
    private final DailyBonusService dailyBonusService;

    @Override
    public PlayerScreen getScreen() {
        return PlayerScreen.MAIN;
    }

    @Override
    public BotResponse handle(Player player, Island island, String text) {
        for (ZoneType zone : ZoneType.values()) {
            if (zone.getDisplayName().equals(text) && zone.isUnlocked(island.getDevPoints())) {
                return enterZone(zone, player, island);
            }
        }
        return switch (text) {
            case BTN_ISLAND -> enterMyIsland(player, island);
            case BTN_INV    -> new BotResponse("⚙️ Инвентарь — скоро!", null, keyboard(player, island));
            default         -> showMainMenu(player, island, tideService);
        };
    }

    private BotResponse enterZone(ZoneType zone, Player player, Island island) {
        player.getState().setCurrentScreen(screenFor(zone));
        playerRepository.save(player);
        return switch (zone) {
            case SHORE      -> {
                IslandBuilding pier = buildingService.find(island, BuildingType.FISHING_PIER).orElse(null);
                yield ShoreZoneHandler.buildZoneScreen(player, tideService, pier);
            }
            case FOREST     -> ForestZoneHandler.buildZoneScreen(player, forestService);
            case SETTLEMENT -> SettlementZoneHandler.buildZoneScreen(player, dailyBonusService);
            case HILLS      -> HillsZoneHandler.buildZoneScreen(player, mineService);
            case PLAINS     -> PlainsZoneHandler.buildZoneScreen(player);
            case PORT       -> PortZoneHandler.buildZoneScreen(player);
        };
    }

    private BotResponse enterMyIsland(Player player, Island island) {
        player.getState().setCurrentScreen(PlayerScreen.MY_ISLAND);
        playerRepository.save(player);
        return MyIslandHandler.buildScreen(player, island);
    }

    // ── Static helpers (called from other handlers on back-navigation) ────────

    /** Упрощённый вариант без TideService — для хэндлеров без доступа к сервису. */
    public static BotResponse showMainMenu(Player player, Island island) {
        return showMainMenu(player, island, null);
    }

    /** Полный вариант — передавай TideService когда он доступен. */
    public static BotResponse showMainMenu(Player player, Island island, TideService tideService) {
        int dp = island.getDevPoints();
        RichText rt = new RichText()
                .bold("🏝 Остров «" + island.getName() + "»")
                .add("\n")
                .emoji(stageEmojiFor(dp)).add(" " + stageNameFor(dp))
                .add(" · " + Fmt.n(dp) + " ОР");

        // Resource dashboard — compact one-liner
        rt.add("\n\n").add(buildResourceLine(island));

        List<String> digest = buildDigest(player, tideService);
        rt.add("\n");
        if (digest.isEmpty()) {
            rt.add("\n" + randomAtmosphere());
        } else {
            digest.forEach(line -> rt.add("\n" + line));
        }

        String tease = nextLockedZoneLine(island);
        if (tease != null) {
            rt.add("\n" + tease);
        }

        return rt.build(keyboard(player, island, tideService));
    }

    /**
     * Compact resource line, e.g. "🪵 45 · 🐟 8 · 🐚 3  📦 56/200"
     * Only non-zero resources are shown.
     */
    private static String buildResourceLine(Island island) {
        StringBuilder sb = new StringBuilder();
        appendRes(sb, "🪵", island.getWood());
        appendRes(sb, "🪨", island.getStone());
        appendRes(sb, "🐟", island.getFish());
        appendRes(sb, "🐚", island.getShells());
        appendRes(sb, "🪸", island.getCoral());

        int total = island.getWood() + island.getStone() + island.getFish()
                + island.getShells() + island.getCoral();
        int cap   = island.getStorageCapacity();

        // Storage fill indicator
        String storageIcon = total >= cap ? "🔴" : total >= cap * 0.8 ? "🟡" : "📦";
        if (sb.length() > 0) sb.append("  ");
        sb.append(storageIcon).append(" ").append(total).append("/").append(cap);

        return sb.toString();
    }

    private static void appendRes(StringBuilder sb, String emoji, int value) {
        if (value <= 0) return;
        if (sb.length() > 0) sb.append(" · ");
        sb.append(emoji).append(" ").append(value);
    }

    public static ReplyKeyboard keyboard(Player player, Island island) {
        return keyboard(player, island, null);
    }

    public static ReplyKeyboard keyboard(Player player, Island island, TideService tideService) {
        int dp = island.getDevPoints();
        KeyboardBuilder kb = KeyboardBuilder.builder();

        // Row 1: always-open zones
        kb.row(
                forestButton(player),
                shoreButton(player, tideService),
                settlementButton(player)
        );

        // Row 2: zones unlocked via ОР (appear when earned)
        List<KeyboardButton> row2 = new ArrayList<>();
        for (ZoneType zone : List.of(ZoneType.HILLS, ZoneType.PLAINS, ZoneType.PORT)) {
            if (zone.isUnlocked(dp)) {
                row2.add(new KeyboardButton(zone.getDisplayName()));
            }
        }
        if (!row2.isEmpty()) {
            kb.row(row2.toArray(new KeyboardButton[0]));
        }

        // Row 3: island summary + inventory
        kb.row(BTN_ISLAND, BTN_INV);

        return kb.build();
    }

    /** Shore: green when fishing ready, tide active, or beach ready. */
    private static KeyboardButton shoreButton(Player player, TideService tideService) {
        KeyboardButton btn = new KeyboardButton(ZoneType.SHORE.getDisplayName());
        LocalDateTime finishAt = player.getFishing().getFishingFinishAt();
        boolean fishingReady = finishAt != null && !LocalDateTime.now().isBefore(finishAt);
        boolean beachAlert   = tideService != null
                && (tideService.isActive(player) || tideService.isBeachReady(player));
        if (fishingReady || beachAlert) btn.setStyle("success");
        return btn;
    }

    /** Forest: green when yield is ready to collect. */
    private static KeyboardButton forestButton(Player player) {
        KeyboardButton btn = new KeyboardButton(ZoneType.FOREST.getDisplayName());
        if (player.getForest() != null) {
            LocalDateTime forestAt = player.getForest().getFinishAt();
            if (forestAt != null && !LocalDateTime.now().isBefore(forestAt)) {
                btn.setStyle("success");
            }
        }
        return btn;
    }

    /** Settlement: green when daily bonus is available. */
    private static KeyboardButton settlementButton(Player player) {
        KeyboardButton btn = new KeyboardButton(ZoneType.SETTLEMENT.getDisplayName());
        LocalDateTime last = player.getState().getDailyBonusAt();
        boolean bonusReady = last == null
                || Duration.between(last, LocalDateTime.now()).toHours() >= 20;
        if (bonusReady) btn.setStyle("success");
        return btn;
    }

    /** Дайджест: активности, которые требуют внимания. Макс. 5 строк. */
    private static List<String> buildDigest(Player player, TideService tideService) {
        List<String> lines = new ArrayList<>();

        // Shore: fishing
        LocalDateTime finishAt = player.getFishing().getFishingFinishAt();
        if (finishAt != null) {
            if (!LocalDateTime.now().isBefore(finishAt)) {
                lines.add("🏖 Улов готов!");
            } else {
                long mins = Math.max(1,
                        (Duration.between(LocalDateTime.now(), finishAt).getSeconds() + 59) / 60);
                lines.add("🏖 Удочка заброшена — ещё ~" + mins + " мин");
            }
        }
        // Shore: tide / beach
        if (tideService != null) {
            if (tideService.isActive(player)) {
                lines.add("🌊 Прилив идёт — не упусти!");
            } else if (tideService.isBeachReady(player)) {
                lines.add("🏖 Пляж: есть что подобрать");
            }
        }

        // Forest
        if (player.getForest() != null) {
            LocalDateTime forestAt = player.getForest().getFinishAt();
            if (forestAt != null) {
                if (!LocalDateTime.now().isBefore(forestAt)) {
                    lines.add("🌲 Добыча из леса готова!");
                } else {
                    long mins = Math.max(1,
                            (Duration.between(LocalDateTime.now(), forestAt).getSeconds() + 59) / 60);
                    lines.add("🌲 Вылазка в лес — ещё ~" + mins + " мин");
                }
            }
        }

        // Hills / Mine
        if (player.getMine() != null) {
            LocalDateTime mineAt = player.getMine().getFinishAt();
            if (mineAt != null) {
                if (!LocalDateTime.now().isBefore(mineAt)) {
                    lines.add("⛰ Добыча из шахты готова!");
                } else {
                    long mins = Math.max(1,
                            (Duration.between(LocalDateTime.now(), mineAt).getSeconds() + 59) / 60);
                    lines.add("⛰ Шахта — ещё ~" + mins + " мин");
                }
            }
        }

        // Settlement: daily bonus
        if (player.getState().getDailyBonusAt() == null
                || Duration.between(player.getState().getDailyBonusAt(), LocalDateTime.now()).toHours() >= 20) {
            lines.add("🎁 Ежедневный бонус доступен!");
        }

        return lines;
    }

    /** Тизер следующей закрытой зоны. */
    private static String nextLockedZoneLine(Island island) {
        int dp = island.getDevPoints();
        for (ZoneType zone : ZoneType.values()) {
            if (zone.getMinDevPoints() > 0 && !zone.isUnlocked(dp)) {
                return "🔒 Следующая зона: " + zone.getDisplayName()
                        + " — " + Fmt.n(dp) + "/" + Fmt.n(zone.getMinDevPoints()) + " ОР";
            }
        }
        return null;
    }

    private static final List<String> ATMOSPHERE = List.of(
            "Над островом кружат чайки.",
            "Бриз с моря несёт запах соли.",
            "Солнце медленно ползёт к горизонту.",
            "Всё спокойно. Остров ждёт.",
            "Приятный тихий день на архипелаге."
    );

    private static String randomAtmosphere() {
        return ATMOSPHERE.get(ThreadLocalRandom.current().nextInt(ATMOSPHERE.size()));
    }

    /** Полная строка «эмодзи + название» — для экранов без RichText (MyIsland и др.). */
    public static String stageFor(int devPoints) {
        EmojiUtil.Def d = stageEmojiFor(devPoints);
        return d.placeholder() + " " + stageNameFor(devPoints);
    }

    public static String stageNameFor(int devPoints) {
        if (devPoints >= 60) return "Расцветающий остров";
        if (devPoints >= 25) return "Оживлённый остров";
        if (devPoints >= 10) return "Молодое поселение";
        return "Дикий клочок земли";
    }

    public static EmojiUtil.Def stageEmojiFor(int devPoints) {
        if (devPoints >= 60) return STAGE_BLOOM;
        if (devPoints >= 25) return STAGE_LIVELY;
        if (devPoints >= 10) return STAGE_YOUNG;
        return STAGE_WILD;
    }

    private static PlayerScreen screenFor(ZoneType zone) {
        return switch (zone) {
            case SHORE      -> PlayerScreen.ZONE_SHORE;
            case FOREST     -> PlayerScreen.ZONE_FOREST;
            case SETTLEMENT -> PlayerScreen.ZONE_SETTLEMENT;
            case HILLS      -> PlayerScreen.ZONE_HILLS;
            case PLAINS     -> PlayerScreen.ZONE_PLAINS;
            case PORT       -> PlayerScreen.ZONE_PORT;
        };
    }
}
