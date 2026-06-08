package com.reefbot.service.game;

import com.reefbot.dto.BotResponse;
import com.reefbot.entity.Player;
import com.reefbot.enums.BuildingType;
import com.reefbot.enums.IslandZone;
import com.reefbot.enums.PlayerScreen;
import com.reefbot.service.game.handlers.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class GameService {

    private final MainMenuHandler    mainMenuHandler;
    private final IslandScreenHandler islandScreenHandler;
    private final BuildMenuHandler   buildMenuHandler;
    private final BuildZoneHandler   buildZoneHandler;
    private final BuildConfirmHandler buildConfirmHandler;

    public BotResponse handle(Player player, String text) {
        PlayerScreen screen = player.getScreen() != null ? player.getScreen() : PlayerScreen.MAIN;

        // ── Global shortcuts (work from any screen) ────────────────────────────
        if (MainMenuHandler.BTN_ISLAND.equals(text))      return islandScreenHandler.handle(player);
        if (MainMenuHandler.BTN_BUILD.equals(text))       return buildMenuHandler.handle(player);
        if (MainMenuHandler.BTN_RESOURCES.equals(text))   return resourcesText(player);
        if (MainMenuHandler.BTN_EXPEDITIONS.equals(text)) return expeditionsSoon(player);
        if ("/start".equals(text))                        return mainMenuHandler.handle(player);

        // ── "← Назад" — navigate up one level ────────────────────────────────
        if (MainMenuHandler.BTN_BACK.equals(text)) {
            return switch (screen) {
                case BUILD_CONFIRM -> {
                    // Back to the zone that contains the pending building
                    String typeName = player.getPendingAction();
                    if (typeName != null) {
                        BuildingType type = BuildingType.valueOf(typeName);
                        yield buildZoneHandler.handle(player, type.getZone());
                    }
                    yield buildMenuHandler.handle(player);
                }
                case BUILD_ZONE -> buildMenuHandler.handle(player);
                default         -> mainMenuHandler.handle(player);
            };
        }

        // ── Context-sensitive routing ─────────────────────────────────────────
        return switch (screen) {

            case BUILD -> {
                // Player tapped a zone button (possibly with 🔒 suffix)
                Optional<IslandZone> zone = zoneFromLabel(text);
                yield zone.isPresent()
                        ? buildZoneHandler.handle(player, zone.get())
                        : mainMenuHandler.handle(player);
            }

            case BUILD_ZONE -> {
                // Player tapped a building name
                Optional<BuildingType> type = BuildingType.fromDisplayName(text);
                yield type.isPresent()
                        ? buildConfirmHandler.showDetail(player, type.get())
                        : mainMenuHandler.handle(player);
            }

            case BUILD_CONFIRM -> {
                if (BuildConfirmHandler.BTN_BUILD.equals(text)) {
                    yield buildConfirmHandler.executeBuild(player);
                }
                // Any other input → re-show detail
                String typeName = player.getPendingAction();
                if (typeName != null) {
                    yield buildConfirmHandler.showDetail(player, BuildingType.valueOf(typeName));
                }
                yield mainMenuHandler.handle(player);
            }

            // MAIN or unknown
            default -> mainMenuHandler.handle(player);
        };
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Match zone button text exactly. Only unlocked zones appear as buttons. */
    private Optional<IslandZone> zoneFromLabel(String label) {
        return Arrays.stream(IslandZone.values())
                .filter(z -> z.getDisplayName().equals(label))
                .findFirst();
    }

    private BotResponse resourcesText(Player player) {
        var island = player.getIsland();
        String text = "📦 Ресурсы острова «" + island.getName() + "»\n\n"
                + "🪵 Древесина: " + island.getWood() + "\n"
                + "🪨 Камень: "    + island.getStone() + "\n"
                + "🐟 Рыба: "      + island.getFish()  + "\n"
                + "🐚 Ракушки: "   + island.getShells() + "\n\n"
                + "Открой остров (🏝 Мой остров) чтобы собрать накопившиеся ресурсы.";
        return new BotResponse(text, null, MainMenuHandler.buildKeyboard());
    }

    private BotResponse expeditionsSoon(Player player) {
        return new BotResponse(
                "⚓ Экспедиции\n\nЭта функция откроется после строительства Рыбацкой пристани. Продолжай развивать остров!",
                null,
                MainMenuHandler.buildKeyboard()
        );
    }
}
