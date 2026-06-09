package com.reefbot.service.registration.steps;

import com.reefbot.dto.BotResponse;
import com.reefbot.entity.Island;
import com.reefbot.entity.Player;
import com.reefbot.enums.OnboardingStep;
import com.reefbot.enums.PlayerScreen;
import com.reefbot.enums.PlayerStatus;
import com.reefbot.service.PlayerService;
import com.reefbot.service.ResourceService;
import com.reefbot.service.game.handlers.MainMenuHandler;
import com.reefbot.service.registration.OnboardingStepHandler;
import com.reefbot.util.KeyboardBuilder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import static com.reefbot.service.registration.OnboardingStepHandler.useKeyboard;
import static com.reefbot.util.EmojiUtil.e;
import static com.reefbot.util.EmojiUtil.entities;

@Component
@RequiredArgsConstructor
public class ReceiveStarterPackStepHandler implements OnboardingStepHandler {

    private static final String NEXT_LABEL = "Получить набор";
    private static final String PHOTO_PATH = "img/reg/reg3.png";

    private static final String PACK_TEXT = """
            🎁 Стартовые ресурсы

            🪵 Древесина ×20
            🪨 Камень ×10
            🎣 Простая удочка

            У старого причала удалось найти несколько полезных припасов.
            Этого должно хватить, чтобы сделать первые шаги.
            """;

    private final PlayerService playerService;
    private final ResourceService resourceService;

    @Override
    public OnboardingStep getStep() {
        return OnboardingStep.RECEIVE_STARTER_PACK;
    }

    @Override
    public BotResponse process(Player player, String message) {
        if (!NEXT_LABEL.equals(message)) {
            // Show starter pack info with photo and button
            return new BotResponse(
                    PACK_TEXT,
                    PHOTO_PATH,
                    useKeyboard(KeyboardBuilder.builder().row(NEXT_LABEL).oneTime(true).build()).keyboard(),
                    entities(PACK_TEXT,
                            e("🎁", "5203996991054432397"),
                            e("🪵", "5188239353045868629"),
                            e("🪨", "5388667686995637270"),
                            e("🎣", "5343609421316521960")
                    )
            );
        }

        // Give starter resources
        Island island = player.getIsland();
        resourceService.giveStarterPack(island); // wood+20, stone+10, saves island

        // Activate player
        player.setOnboardingStep(OnboardingStep.FINISHED);
        player.setStatus(PlayerStatus.ACTIVE);
        player.getState().setCurrentScreen(PlayerScreen.MAIN);
        player.getFishing().setFishingLevel(1);
        player.getFishing().setFishingXp(0);
        playerService.save(player);

        // Show main menu immediately with fishing hint
        String welcomeText = String.format("""
                🏝 Остров «%s» готов к развитию!

                🎣 В стартовом наборе нашлась старая удочка.
                Самое время испытать её — нажми «Рыбалка».

                %s · %d ОР
                """,
                island.getName(),
                MainMenuHandler.stageFor(island.getDevPoints()),
                island.getDevPoints());

        return new BotResponse(welcomeText, null, MainMenuHandler.keyboard(player));
    }
}
