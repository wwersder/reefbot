package com.reefbot.service.game.handlers;

import com.reefbot.dto.BotResponse;
import com.reefbot.entity.Building;
import com.reefbot.entity.Island;
import com.reefbot.entity.Player;
import com.reefbot.enums.BuildingType;
import com.reefbot.enums.PlayerScreen;
import com.reefbot.service.BuildingService;
import com.reefbot.service.BuildingService.BuildState;
import com.reefbot.service.PlayerService;
import com.reefbot.service.ResourceService;
import com.reefbot.util.KeyboardBuilder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class BuildConfirmHandler {

    public static final String BTN_BUILD = "✅ Построить";

    private final BuildingService   buildingService;
    private final ResourceService   resourceService;
    private final PlayerService     playerService;
    private final IslandScreenHandler islandScreenHandler;

    /** Show building detail and "Построить" button. */
    public BotResponse showDetail(Player player, BuildingType type) {
        Island island = player.getIsland();
        List<Building> all = buildingService.getBuiltBuildings(island);
        all.addAll(buildingService.getInProgressBuildings(island));
        int dp = buildingService.getDevelopmentPoints(buildingService.getBuiltBuildings(island));

        BuildState state = buildingService.getBuildState(island, type, all, dp);

        StringBuilder sb = new StringBuilder();
        sb.append(type.getDisplayName()).append("\n\n");
        sb.append(type.getEffectDescription()).append("\n\n");

        // Prerequisites
        if (!type.getPrerequisites().isEmpty()) {
            sb.append("Требования:\n");
            type.getPrerequisites().forEach(req -> {
                boolean met = buildingService.isBuilt(island, req);
                sb.append("  ").append(met ? "✅" : "❌").append(" ").append(req.getDisplayName()).append("\n");
            });
            sb.append("\n");
        }

        sb.append("Стоимость: ").append(resourceService.formatCost(type.getBuildCost())).append("\n");
        sb.append("Время: ").append(formatDuration(type)).append("\n");
        sb.append("Очки развития: +").append(type.getDevelopmentPoints()).append(" ОР\n");

        // Current resources
        sb.append("\nЕсть в наличии: ");
        sb.append("🪵").append(island.getWood())
          .append("  🪨").append(island.getStone())
          .append("  🐟").append(island.getFish());

        KeyboardBuilder kb = KeyboardBuilder.builder();

        if (state == BuildState.AVAILABLE) {
            kb.row(BTN_BUILD);
        } else if (state == BuildState.LOCKED_RESOURCES) {
            sb.append("\n\n❗ Недостаточно ресурсов для постройки.");
        } else if (state == BuildState.LOCKED_REQUIREMENTS) {
            sb.append("\n\n🔒 Сначала постройте необходимые здания.");
        } else if (state == BuildState.IN_PROGRESS) {
            sb.append("\n\n⏳ Уже строится.");
        } else if (state == BuildState.BUILT) {
            sb.append("\n\n✅ Уже построено.");
        }

        kb.row(MainMenuHandler.BTN_BACK);

        player.setScreen(PlayerScreen.BUILD_CONFIRM);
        player.setPendingAction(type.name());
        playerService.save(player);

        return new BotResponse(sb.toString(), null, kb.build());
    }

    /** Execute the build when player presses "✅ Построить". */
    public BotResponse executeBuild(Player player) {
        String typeName = player.getPendingAction();
        if (typeName == null) return islandScreenHandler.handle(player);

        BuildingType type = BuildingType.valueOf(typeName);
        Island island = player.getIsland();

        List<Building> all = buildingService.getBuiltBuildings(island);
        all.addAll(buildingService.getInProgressBuildings(island));
        int dp = buildingService.getDevelopmentPoints(buildingService.getBuiltBuildings(island));

        BuildState state = buildingService.getBuildState(island, type, all, dp);

        if (state != BuildState.AVAILABLE) {
            return showDetail(player, type);  // re-show with updated status
        }

        Building building = buildingService.startBuild(island, type);

        String timeStr = formatDuration(type);
        String text = "🔨 Строительство началось!\n\n"
                + type.getDisplayName()
                + " будет готово через " + timeStr + ".\n\n"
                + "Я сообщу когда всё будет готово.";

        // Return to island screen
        player.setScreen(PlayerScreen.MAIN);
        player.setPendingAction(null);
        playerService.save(player);

        return new BotResponse(text, null, MainMenuHandler.buildKeyboard());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String formatDuration(BuildingType type) {
        long hours   = type.getBuildDuration().toHours();
        long minutes = type.getBuildDuration().toMinutesPart();
        if (hours > 0) return hours + " ч " + (minutes > 0 ? minutes + " мин" : "");
        return minutes + " мин";
    }
}
