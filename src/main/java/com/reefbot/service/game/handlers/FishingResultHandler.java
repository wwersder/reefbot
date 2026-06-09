package com.reefbot.service.game.handlers;

import com.reefbot.dto.BotResponse;
import com.reefbot.entity.Island;
import com.reefbot.entity.Player;
import com.reefbot.enums.PlayerScreen;
import com.reefbot.repository.PlayerRepository;
import com.reefbot.service.game.FishingResult;
import com.reefbot.service.game.FishingService;
import com.reefbot.service.game.GameHandler;
import com.reefbot.util.KeyboardBuilder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class FishingResultHandler implements GameHandler {

    private static final String BTN_COLLECT = "✅ Забрать улов";

    private final FishingService fishingService;
    private final PlayerRepository playerRepository;

    @Override
    public PlayerScreen getScreen() {
        return PlayerScreen.FISHING_RESULT;
    }

    @Override
    public BotResponse handle(Player player, Island island, String text) {
        if (!BTN_COLLECT.equals(text)) {
            // Show the result screen again if player sends something unexpected
            return buildResultScreen(player, island, fishingService);
        }

        FishingResult result = fishingService.collectFish(player, island);

        player.setCurrentScreen(PlayerScreen.MAIN);
        playerRepository.save(player);

        return buildCollectedResponse(result, player, island);
    }

    private BotResponse buildCollectedResponse(FishingResult result, Player player, Island island) {
        StringBuilder sb = new StringBuilder();
        sb.append("🎉 Улов готов!\n\n");
        sb.append("Место: ").append(result.spot().getDisplayName()).append("\n");
        sb.append("Поймал: 🐟 ×").append(result.fishCaught());

        if (result.bonusType() != null) {
            String bonusEmoji = switch (result.bonusType()) {
                case SHELLS -> "🐚";
                case CORAL  -> "🪸";
                default     -> "✨";
            };
            sb.append(", ").append(bonusEmoji).append(" ×").append(result.bonusAmount());
        }

        int nextLevelXp = fishingService.xpForNextLevel(result.newLevel());
        sb.append("\n\nОпыт рыбака: +").append(result.xpEarned()).append(" ⭐");
        if (nextLevelXp > 0) {
            sb.append("  (всего ").append(result.totalXp()).append("/").append(nextLevelXp).append(")");
        }

        if (result.wasFirstCatch()) {
            sb.append("\n\n🎣 Первый улов! Рыбалка — хороший способ пополнить запасы.");
        }

        String stage = MainMenuHandler.stageFor(island.getDevPoints());
        String menuText = String.format("\n\n🏝 Остров «%s»\n%s · %d ОР",
                island.getName(), stage, island.getDevPoints());
        sb.append(menuText);

        return new BotResponse(sb.toString(), null, MainMenuHandler.keyboard(player));
    }

    /** Build the "Забрать улов" screen shown before collection. */
    public static BotResponse buildResultScreen(Player player, Island island, FishingService fishingService) {
        String spot = player.getFishingSpot() != null
                ? player.getFishingSpot().getDisplayName()
                : "неизвестно";

        String text = String.format("""
                🔔 Улов готов!

                Место: %s
                Жми «Забрать» чтобы получить ресурсы.
                """, spot);

        return new BotResponse(text, null,
                KeyboardBuilder.builder().row(BTN_COLLECT).build());
    }
}
