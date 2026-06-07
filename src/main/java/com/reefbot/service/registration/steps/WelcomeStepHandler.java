package com.reefbot.service.registration.steps;

import com.reefbot.dto.BotResponse;
import com.reefbot.entity.Player;
import com.reefbot.enums.OnboardingStep;
import com.reefbot.service.PlayerService;
import com.reefbot.service.registration.OnboardingStepHandler;
import com.reefbot.util.KeyboardBuilder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import static com.reefbot.util.EmojiUtil.e;
import static com.reefbot.util.EmojiUtil.entities;

@Component
@RequiredArgsConstructor
public class WelcomeStepHandler implements OnboardingStepHandler {

    private static final String CONTINUE_LABEL = "Продолжить";
    private static final String PHOTO_PATH = "img/reg/reg1.png";

    private static final String TEXT = """
            🌴 Где-то среди бескрайнего архипелага находится небольшой остров.

            На картах он ничем не примечателен.
            На нём нет богатств и величественных построек.

            Пока что.

            ⚡️ С этого дня остров принадлежит тебе.""";

    private final PlayerService playerService;

    @Override
    public OnboardingStep getStep() {
        return OnboardingStep.WELCOME;
    }

    @Override
    public BotResponse process(Player player, String message) {
        player.setOnboardingStep(OnboardingStep.ISLAND_OVERVIEW);
        playerService.save(player);

        return new BotResponse(
                TEXT,
                PHOTO_PATH,
                KeyboardBuilder.builder().row(CONTINUE_LABEL).oneTime(true).build(),
                entities(TEXT,
                        e("🌴", "5807538646030489502"),
                        e("⚡️", "5807485199457458084")
                )
        );
    }
}
