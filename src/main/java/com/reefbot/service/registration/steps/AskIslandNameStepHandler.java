package com.reefbot.service.registration.steps;

import com.reefbot.dto.BotResponse;
import com.reefbot.entity.Player;
import com.reefbot.enums.OnboardingStep;
import com.reefbot.service.PlayerService;
import com.reefbot.service.registration.OnboardingStepHandler;
import com.reefbot.util.KeyboardBuilder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import static com.reefbot.service.registration.OnboardingStepHandler.useKeyboard;
import static com.reefbot.util.EmojiUtil.e;
import static com.reefbot.util.EmojiUtil.entities;

@Component
@RequiredArgsConstructor
public class AskIslandNameStepHandler implements OnboardingStepHandler {

    private static final String CONTINUE_LABEL = "Продолжить";

    private final PlayerService playerService;

    private static final String TEXT = """
            🌴 До сегодняшнего дня этот остров был лишь безымянной точкой на карте.
            
            Пришло время дать ему имя.
            
            Введите название острова:""";

    @Override
    public OnboardingStep getStep() {
        return OnboardingStep.ASK_ISLAND_NAME;
    }

    @Override
    public BotResponse process(Player player, String message) {
        if (!CONTINUE_LABEL.equals(message)) {
            return useKeyboard(KeyboardBuilder.builder().row(CONTINUE_LABEL).oneTime(true).build());
        }

        player.setOnboardingStep(OnboardingStep.ENTER_ISLAND_NAME);
        playerService.save(player);

        return new BotResponse(TEXT, entities(TEXT, e("🌴", "5807538646030489502")));
    }
}
