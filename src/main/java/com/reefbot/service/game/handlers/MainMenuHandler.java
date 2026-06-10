package com.reefbot.service.game.handlers;

import com.reefbot.dto.BotResponse;
import com.reefbot.entity.Island;
import com.reefbot.entity.Player;
import com.reefbot.enums.PlayerScreen;
import com.reefbot.enums.ZoneType;
import com.reefbot.repository.PlayerRepository;
import com.reefbot.service.game.GameHandler;
import com.reefbot.util.KeyboardBuilder;
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

    private final PlayerRepository playerRepository;

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
            default         -> showMainMenu(player, island);
        };
    }

    private BotResponse enterZone(ZoneType zone, Player player, Island island) {
        player.getState().setCurrentScreen(screenFor(zone));
        playerRepository.save(player);
        return switch (zone) {
            case SHORE      -> ShoreZoneHandler.buildZoneScreen(player);
            case FOREST     -> ForestZoneHandler.buildZoneScreen(player);
            case SETTLEMENT -> SettlementZoneHandler.buildZoneScreen(player);
            case HILLS      -> HillsZoneHandler.buildZoneScreen(player);
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

    public static BotResponse showMainMenu(Player player, Island island) {
        String stage = stageFor(island.getDevPoints());
        StringBuilder sb = new StringBuilder()
                .append("🏝 Остров «").append(island.getName()).append("»\n")
                .append(stage).append(" · ").append(island.getDevPoints()).append(" ОР");

        List<String> digest = buildDigest(player);
        sb.append("\n");
        if (digest.isEmpty()) {
            sb.append("\n").append(randomAtmosphere());
        } else {
            digest.forEach(line -> sb.append("\n").append(line));
        }

        String tease = nextLockedZoneLine(island);
        if (tease != null) {
            sb.append("\n").append(tease);
        }

        return new BotResponse(sb.toString(), null, keyboard(player, island));
    }

    public static ReplyKeyboard keyboard(Player player, Island island) {
        int dp = island.getDevPoints();
        KeyboardBuilder kb = KeyboardBuilder.builder();

        // Row 1: always-open zones
        kb.row(
                new KeyboardButton(ZoneType.FOREST.getDisplayName()),
                shoreButton(player),
                new KeyboardButton(ZoneType.SETTLEMENT.getDisplayName())
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

    /** Shore-zone button зеленеет если есть готовый улов. */
    private static KeyboardButton shoreButton(Player player) {
        KeyboardButton btn = new KeyboardButton(ZoneType.SHORE.getDisplayName());
        LocalDateTime finishAt = player.getFishing().getFishingFinishAt();
        if (finishAt != null && !LocalDateTime.now().isBefore(finishAt)) {
            btn.setStyle("success");
        }
        return btn;
    }

    /** Дайджест: активности, которые требуют внимания. Макс. 4 строки. */
    private static List<String> buildDigest(Player player) {
        List<String> lines = new ArrayList<>();
        // Shore: fishing (единственная реализованная зона)
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
        // TODO: ZoneStatusProvider per zone when more activities are implemented
        return lines;
    }

    /** Тизер следующей закрытой зоны. */
    private static String nextLockedZoneLine(Island island) {
        int dp = island.getDevPoints();
        for (ZoneType zone : ZoneType.values()) {
            if (zone.getMinDevPoints() > 0 && !zone.isUnlocked(dp)) {
                return "🔒 Следующая зона: " + zone.getDisplayName()
                        + " — " + dp + "/" + zone.getMinDevPoints() + " ОР";
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

    public static String stageFor(int devPoints) {
        if (devPoints >= 61) return "🏙 Процветающий город";
        if (devPoints >= 21) return "⚓ Развивающийся порт";
        if (devPoints >= 7)  return "🏘 Рыбацкая деревня";
        return "🌱 Дикий островок";
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
