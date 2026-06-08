package com.reefbot.service.game.handlers;

import com.reefbot.dto.BotResponse;
import com.reefbot.entity.Building;
import com.reefbot.entity.Island;
import com.reefbot.entity.Player;
import com.reefbot.enums.BuildingType;
import com.reefbot.enums.IslandZone;
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
public class BuildZoneHandler {

    private final BuildingService buildingService;
    private final ResourceService resourceService;
    private final PlayerService   playerService;

    public BotResponse handle(Player player, IslandZone zone) {
        Island island = player.getIsland();
        List<Building> allBuildings  = buildingService.getBuiltBuildings(island);
        allBuildings.addAll(buildingService.getInProgressBuildings(island));
        int dp = buildingService.getDevelopmentPoints(buildingService.getBuiltBuildings(island));

        List<BuildingType> types = BuildingType.inZone(zone);

        StringBuilder sb = new StringBuilder();
        sb.append(zone.getDisplayName()).append("\n\n");

        KeyboardBuilder kb = KeyboardBuilder.builder();

        for (BuildingType type : types) {
            BuildState state = buildingService.getBuildState(island, type, allBuildings, dp);

            switch (state) {
                case BUILT ->
                    sb.append("✅ ").append(type.getDisplayName())
                      .append(" — построена\n");
                case IN_PROGRESS ->
                    sb.append("⏳ ").append(type.getDisplayName())
                      .append(" — строится...\n");
                case AVAILABLE -> {
                    sb.append("🔨 ").append(type.getDisplayName())
                      .append(" — ")
                      .append(resourceService.formatCost(type.getBuildCost()))
                      .append(" | ").append(formatDuration(type))
                      .append("\n");
                    kb.row(type.getDisplayName());   // tappable button
                }
                case LOCKED_RESOURCES -> {
                    sb.append("💰 ").append(type.getDisplayName())
                      .append(" — недостаточно ресурсов\n");
                    kb.row(type.getDisplayName());   // still tappable — shows detail with cost info
                }
                case LOCKED_REQUIREMENTS ->
                    sb.append("🔒 ").append(type.getDisplayName())
                      .append(" — нужны предпосылки\n");
                case LOCKED_ZONE ->
                    sb.append("🔒 ").append(type.getDisplayName())
                      .append(" — зона не открыта\n");
            }
        }

        kb.row(MainMenuHandler.BTN_BACK);

        player.setScreen(PlayerScreen.BUILD_ZONE);
        player.setPendingAction(zone.name());
        playerService.save(player);

        return new BotResponse(sb.toString(), null, kb.build());
    }

    private String formatDuration(BuildingType type) {
        long hours   = type.getBuildDuration().toHours();
        long minutes = type.getBuildDuration().toMinutesPart();
        if (hours > 0) return hours + " ч " + minutes + " мин";
        return minutes + " мин";
    }
}
