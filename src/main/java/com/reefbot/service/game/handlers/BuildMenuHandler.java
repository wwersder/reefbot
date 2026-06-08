package com.reefbot.service.game.handlers;

import com.reefbot.dto.BotResponse;
import com.reefbot.entity.Building;
import com.reefbot.entity.Island;
import com.reefbot.entity.Player;
import com.reefbot.enums.IslandZone;
import com.reefbot.enums.PlayerScreen;
import com.reefbot.service.BuildingService;
import com.reefbot.service.PlayerService;
import com.reefbot.util.KeyboardBuilder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@RequiredArgsConstructor
public class BuildMenuHandler {

    private final BuildingService buildingService;
    private final PlayerService   playerService;

    private static final IslandZone[] DISPLAY_ZONES = {
            IslandZone.FOREST,
            IslandZone.COASTAL,
            IslandZone.SETTLEMENT,
            IslandZone.HILL,
            IslandZone.PLAIN
    };

    public BotResponse handle(Player player) {
        Island island = player.getIsland();
        List<Building> builtBuildings = buildingService.getBuiltBuildings(island);
        int dp = buildingService.getDevelopmentPoints(builtBuildings);

        player.setScreen(PlayerScreen.BUILD);
        player.setPendingAction(null);
        playerService.save(player);

        StringBuilder sb = new StringBuilder("🏗 Строительство\n\nВыбери зону острова:\n\n");

        List<String> unlockedButtons = new ArrayList<>();

        for (IslandZone zone : DISPLAY_ZONES) {
            if (zone.isUnlocked(dp)) {
                sb.append("✅ ").append(zone.getDisplayName()).append("\n");
                unlockedButtons.add(zone.getDisplayName());
            } else {
                sb.append("🔒 ").append(zone.getDisplayName())
                  .append(" — нужно ").append(zone.getRequiredDevelopmentPoints()).append(" ОР\n");
            }
        }

        sb.append("\nТвои очки развития: ").append(dp).append(" ОР");

        // Build keyboard: pairs of unlocked zone buttons
        KeyboardBuilder kb = KeyboardBuilder.builder();
        for (int i = 0; i < unlockedButtons.size(); i += 2) {
            if (i + 1 < unlockedButtons.size()) {
                kb.row(unlockedButtons.get(i), unlockedButtons.get(i + 1));
            } else {
                kb.row(unlockedButtons.get(i));
            }
        }
        kb.row(MainMenuHandler.BTN_BACK);

        return new BotResponse(sb.toString(), null, kb.build());
    }
}
