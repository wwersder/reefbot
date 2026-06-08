package com.reefbot.service.game.handlers;

import com.reefbot.dto.BotResponse;
import com.reefbot.entity.Building;
import com.reefbot.entity.Island;
import com.reefbot.entity.Player;
import com.reefbot.enums.BuildingStatus;
import com.reefbot.enums.PlayerScreen;
import com.reefbot.service.BuildingService;
import com.reefbot.service.PlayerService;
import com.reefbot.service.ResourceService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

@Component
@RequiredArgsConstructor
public class IslandScreenHandler {

    private final BuildingService buildingService;
    private final PlayerService   playerService;

    public BotResponse handle(Player player) {
        Island island = player.getIsland();

        // 1. Complete any builds whose timer expired while player was away
        List<Building> justCompleted = buildingService.completeFinishedBuilds(island);

        // 2. Collect passive resources from all built buildings
        List<Building> builtBuildings = buildingService.getBuiltBuildings(island);
        buildingService.collectResources(island, builtBuildings);

        // 3. Refresh built list after collection (island resources updated)
        builtBuildings = buildingService.getBuiltBuildings(island);
        List<Building> inProgress = buildingService.getInProgressBuildings(island);
        int dp       = buildingService.getDevelopmentPoints(builtBuildings);
        int capacity = buildingService.getStorageCapacity(builtBuildings);

        // 4. Build screen text
        StringBuilder sb = new StringBuilder();
        sb.append("🏝 Остров «").append(island.getName()).append("»\n");
        sb.append(stageLabel(dp)).append(" • ").append(dp).append(" ОР\n");
        sb.append("\n");

        if (!justCompleted.isEmpty()) {
            sb.append("🎉 Только что построено:\n");
            justCompleted.forEach(b -> sb.append("  ✅ ").append(b.getType().getDisplayName()).append("\n"));
            sb.append("\n");
        }

        sb.append("📦 Склад (макс. ").append(capacity).append("):\n");
        sb.append("  ").append(ResourceService.formatResource("🪵", "Дерево", island.getWood(), capacity)).append("\n");
        sb.append("  ").append(ResourceService.formatResource("🪨", "Камень", island.getStone(), capacity)).append("\n");
        sb.append("  ").append(ResourceService.formatResource("🐟", "Рыба",   island.getFish(),  capacity)).append("\n");
        sb.append("  ").append("🐚 Ракушки: ").append(island.getShells()).append("\n");

        if (!builtBuildings.isEmpty()) {
            sb.append("\n🏗 Постройки:\n");
            builtBuildings.forEach(b -> sb.append("  ✅ ").append(b.getType().getDisplayName())
                    .append(b.getType().getProduces() != null
                            ? " — +" + b.getType().getProducesAmountPerHour()
                              + b.getType().getProduces().getEmoji() + "/час"
                            : " — " + b.getType().getEffectDescription())
                    .append("\n"));
        }

        if (!inProgress.isEmpty()) {
            sb.append("\n⏳ Строится:\n");
            inProgress.forEach(b -> sb.append("  🔨 ")
                    .append(b.getType().getDisplayName())
                    .append(" — осталось ")
                    .append(formatRemaining(b.getFinishAt()))
                    .append("\n"));
        }

        if (builtBuildings.isEmpty() && inProgress.isEmpty()) {
            sb.append("\nОстров пустой. Начни строить! Используй кнопку 🏗 Строить.");
        }

        player.setScreen(PlayerScreen.MAIN);
        player.setPendingAction(null);
        playerService.save(player);

        return new BotResponse(sb.toString(), null, MainMenuHandler.buildKeyboard());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String stageLabel(int dp) {
        if (dp < 7)  return "🌱 Дикий островок";
        if (dp < 21) return "🏘 Рыбацкая деревня";
        if (dp < 61) return "⚓ Развивающийся порт";
        return "🏙 Процветающий город";
    }

    private String formatRemaining(LocalDateTime finishAt) {
        Duration remaining = Duration.between(LocalDateTime.now(), finishAt);
        if (remaining.isNegative()) return "завершается...";
        long h = remaining.toHours();
        long m = remaining.toMinutesPart();
        if (h > 0) return h + " ч " + m + " мин";
        if (m > 0) return m + " мин";
        return "< 1 мин";
    }
}
