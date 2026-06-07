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
public class IslandOverviewStepHandler implements OnboardingStepHandler {

    private static final String CONTINUE_LABEL = "Продолжить";
    private static final String PHOTO_PATH = "img/reg/reg2.png";

    private final PlayerService playerService;

    private static final String TEXT = """
           Осмотр острова завершён.
           
           🌳 Лес обеспечит древесиной.
           🏖 Берег подойдёт для будущих построек.
           🛥 Старый причал позволит принимать корабли.

           Похоже, здесь можно построить нечто большее.""";

    @Override
    public OnboardingStep getStep() {
        return OnboardingStep.ISLAND_OVERVIEW;
    }

    @Override
    public BotResponse process(Player player, String message) {
        if (!CONTINUE_LABEL.equals(message)) {
            return useKeyboard(KeyboardBuilder.builder().row(CONTINUE_LABEL).oneTime(true).build());
        }

        player.setOnboardingStep(OnboardingStep.ASK_ISLAND_NAME);
        playerService.save(player);

        return new BotResponse(
                TEXT,
                PHOTO_PATH,
                KeyboardBuilder.builder().row(CONTINUE_LABEL).oneTime(true).build(),
                entities(TEXT,
                        e("🌳", "5449918202718985124"),
                        e("🏖", "5433645645376264953"),
                        e("🛥", "5359437440954147777")
                )
        );
    }
}
