package com.reefbot.service.game.handlers;

import com.reefbot.dto.BotResponse;
import com.reefbot.entity.Island;
import com.reefbot.entity.Player;
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
            return FishingMenuHandler.buildFishingMenu(player);
        }
        return buildBonusesScreen(player);
    }

    public static BotResponse buildBonusesScreen(Player player) {
        int level = player.getFishing().getFishingLevel();
        StringBuilder sb = new StringBuilder();
        sb.append("✨ <b>Активные бонусы рыбака</b>\n\n");
        sb.append(FishingService.levelName(level)).append(" · Уровень ").append(level).append("\n\n");

        boolean anyBonus = false;
        if (level >= 2) {
            sb.append("🐟 <b>+1</b> к минимальному улову на всех местах\n");
            anyBonus = true;
        }
        if (level >= 4) {
            sb.append("⭐ <b>+10% XP</b> за каждую рыбалку\n");
            anyBonus = true;
        }
        if (level >= 5) {
            sb.append("🎲 <b>+20%</b> шанс бонусного ресурса\n");
            anyBonus = true;
        }
        if (level >= 7) {
            sb.append("⭐ <b>+30% XP</b> за рыбалку (вместо +10%)\n");
            anyBonus = true;
        }
        if (level >= 8) {
            sb.append("🐟 <b>+2</b> к максимальному улову на всех местах\n");
            anyBonus = true;
        }
        if (!anyBonus) {
            sb.append("Пока нет активных бонусов.\nДостигни уровня 2, чтобы начать получать их.");
        }

        // Next bonus hint
        String nextUnlock = FishingService.levelUnlockText(level + 1);
        if (nextUnlock != null && level < 10) {
            int xpNeeded = nextLevelXp(level);
            sb.append("\n\n<i>Следующий бонус на уровне ").append(level + 1)
              .append(" (через ").append(xpNeeded).append(" XP):</i>\n")
              .append(nextUnlock);
        }

        return BotResponse.html(sb.toString(),
                KeyboardBuilder.builder().row(BTN_BACK).build());
    }

    private static int nextLevelXp(int currentLevel) {
        // XP remaining to next level (uses same thresholds as FishingService)
        int[] thresholds = {0, 50, 150, 350, 700, 1200, 2000, 3500, 6000, 10000};
        if (currentLevel >= thresholds.length) return 0;
        return thresholds[currentLevel];
    }
}
