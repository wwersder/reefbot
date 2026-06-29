package com.reefbot.service.game.handlers;

import com.reefbot.dto.BotResponse;
import com.reefbot.entity.Island;
import com.reefbot.entity.Player;
import com.reefbot.enums.PlayerScreen;
import com.reefbot.repository.PlayerRepository;
import com.reefbot.service.game.DailyBonusService;
import com.reefbot.service.game.DailyBonusService.DailyBonusResult;
import com.reefbot.service.game.GameHandler;
import com.reefbot.util.KeyboardBuilder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardButton;

@Component
@RequiredArgsConstructor
public class SettlementBonusHandler implements GameHandler {

    private final DailyBonusService dailyBonusService;
    private final PlayerRepository playerRepository;

    @Override
    public PlayerScreen getScreen() {
        return PlayerScreen.ZONE_SETTLEMENT_BONUS;
    }

    @Override
    public BotResponse handle(Player player, Island island, String text) {
        return switch (text) {
            case SettlementZoneHandler.BTN_COLLECT -> collectBonus(player, island);
            case SettlementZoneHandler.BTN_BACK    -> goBack(player, island);
            default -> SettlementZoneHandler.buildBonusScreen(player, island, dailyBonusService);
        };
    }

    private BotResponse collectBonus(Player player, Island island) {
        if (!dailyBonusService.canClaim(player)) {
            return SettlementZoneHandler.buildBonusScreen(player, island, dailyBonusService);
        }
        DailyBonusResult result = dailyBonusService.claim(player, island);
        player.getState().setCurrentScreen(PlayerScreen.ZONE_SETTLEMENT);
        playerRepository.save(player);

        StringBuilder sb = new StringBuilder("🎉 <b>Бонус получен!</b>\n\n");
        sb.append("<b>🐚 Ракушки:</b> +").append(result.shellsGained()).append("\n");
        sb.append("<b>🐟 Рыба:</b> +").append(result.fishGained()).append("\n");
        sb.append("<b>Серия:</b> ").append(result.streak()).append(" дн.");
        if (result.streak() >= 7) sb.append("\n\n🌟 <b>Максимальная серия!</b>");

        BotResponse resultMsg  = BotResponse.html(sb.toString());
        BotResponse zoneScreen = SettlementZoneHandler.buildZoneScreen(player, dailyBonusService);
        return resultMsg.withFollowUp(zoneScreen);
    }

    private BotResponse goBack(Player player, Island island) {
        player.getState().setCurrentScreen(PlayerScreen.ZONE_SETTLEMENT);
        playerRepository.save(player);
        return SettlementZoneHandler.buildZoneScreen(player, dailyBonusService);
    }
}
