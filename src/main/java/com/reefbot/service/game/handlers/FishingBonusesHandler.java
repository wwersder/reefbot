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
public class FishingBonusesHandler implements GameHandler {

    public static final String BTN_BACK = "◀️ Назад";

    private final PlayerRepository playerRepository;

    @Override
    public PlayerScreen getScreen() {
        return PlayerScreen.FISHING_BONUSES;
    }

    @Override
    public BotResponse handle(Player player, Island island, String text) {
        if (BTN_BACK.equals(text)) {
            player.getState().setCurrentScreen(PlayerScreen.FISHING_MENU);
            playerRepository.save(player);
            // Return to spot detail if spot is selected, otherwise fishing menu
            FishingSpot spot = player.getFishing().getFishingSpot();
            if (spot != null) {
                return FishingMenuHandler.buildSpotDetail(spot, player);
            }
            return FishingMenuHandler.buildFishingMenu(player);
        }
        return buildBonusesScreen(player);
    }

    public static BotResponse buildBonusesScreen(Player player) {
        int level = player.getFishing().getFishingLevel();

        StringBuilder sb = new StringBuilder();
        sb.append("✨ <b>Бонусы рыбака</b>\n\n");

        // Level bonuses
        sb.append("🎣 <b>За уровень рыбака</b>\n");
        sb.append(FishingService.levelName(level)).append(" · Ур. ").append(level).append("\n\n");

        boolean anyBonus = false;
        if (level >= 2) { sb.append("• +1 к мин. улову на всех местах\n"); anyBonus = true; }
        if (level >= 4) { sb.append("• +10% XP за рыбалку\n"); anyBonus = true; }
        if (level >= 5) { sb.append("• +20% шанс бонусного ресурса\n"); anyBonus = true; }
        if (level >= 7) { sb.append("• +30% XP за рыбалку (вместо +10%)\n"); anyBonus = true; }
        if (level >= 8) { sb.append("• +2 к макс. улову на всех местах\n"); anyBonus = true; }

        if (!anyBonus) {
            sb.append("Пока нет — достигни уровня 2\n");
        }

        return BotResponse.html(sb.toString(),
                KeyboardBuilder.builder().row(BTN_BACK).build());
    }
}
