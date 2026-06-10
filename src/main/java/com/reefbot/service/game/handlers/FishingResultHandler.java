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
import com.reefbot.util.ReefEmoji;
import com.reefbot.util.RichText;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class FishingResultHandler implements GameHandler {

    public static final String BTN_COLLECT = "✅ Забрать улов";

    private final FishingService fishingService;
    private final PlayerRepository playerRepository;

    @Override
    public PlayerScreen getScreen() {
        return PlayerScreen.FISHING_RESULT;
    }

    @Override
    public BotResponse handle(Player player, Island island, String text) {
        if (!BTN_COLLECT.equals(text)) {
            return buildResultScreen(player, island, fishingService);
        }

        FishingResult result = fishingService.collectFish(player, island);

        // После сбора игрок возвращается в зону «Берег» (навигация: остров → зона → активность)
        player.getState().setCurrentScreen(PlayerScreen.ZONE_SHORE);
        playerRepository.save(player);

        return buildCollectedResponse(result, player, island);
    }

    private BotResponse buildCollectedResponse(FishingResult result, Player player, Island island) {
        RichText rt = new RichText();
        rt.emoji(ReefEmoji.PARTY).add(" ").bold("Улов!").add("\n\n")
          .bold("Место:").add(" ").emoji(FishingMenuHandler.spotEmojiDef(result.spot()))
                                  .add(" " + FishingMenuHandler.spotName(result.spot())).add("\n")
          .bold("Поймал:").add(" ").emoji(ReefEmoji.FISH).add(" ×" + result.fishCaught());

        if (result.bonusType() != null) {
            String bonusChar = switch (result.bonusType()) {
                case SHELLS -> "🐚";
                case CORAL  -> "🪸";
                default     -> "✨";
            };
            rt.add(", " + bonusChar + " ×" + result.bonusAmount());
        }

        rt.add("\n")
          .bold("Опыт рыбака:").add(" +" + result.xpEarned() + " ").emoji(ReefEmoji.STAR);

        int nextLevelXp = fishingService.xpForNextLevel(result.newLevel());
        if (nextLevelXp > 0) {
            rt.add("  (всего " + result.totalXp() + "/" + nextLevelXp + ")");
        }

        if (result.wasFirstCatch()) {
            rt.add("\n\n").emoji(ReefEmoji.FISHING).add(" Первый улов! Рыбалка — хороший способ пополнить запасы.");
        }

        BotResponse catchMessage = rt.build();
        BotResponse zoneScreen = ShoreZoneHandler.buildZoneScreen(player);

        if (result.leveledUp()) {
            BotResponse levelUpMessage = buildLevelUpMessage(result.newLevel());
            return catchMessage.withFollowUp(levelUpMessage.withFollowUp(zoneScreen));
        }

        return catchMessage.withFollowUp(zoneScreen);
    }

    private static BotResponse buildLevelUpMessage(int newLevel) {
        String name = FishingService.levelName(newLevel);
        String unlockText = FishingService.levelUnlockText(newLevel);

        String bonus = unlockText != null
                ? "<b>" + unlockText + "</b>"
                : "Продолжай рыбачить — впереди ещё много открытий.";

        String text = "🎊 Уровень рыбака повышен!\n\n"
                + name + " · Уровень " + newLevel + "\n\n"
                + bonus;

        return BotResponse.html(text);
    }

    public static BotResponse buildResultScreen(Player player, Island island, FishingService fishingService) {
        String spot = player.getFishing().getFishingSpot() != null
                ? player.getFishing().getFishingSpot().getDisplayName()
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
