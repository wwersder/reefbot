package com.reefbot.service.registration.steps;

import com.reefbot.dto.BotResponse;
import com.reefbot.entity.Player;
import com.reefbot.enums.OnboardingStep;
import com.reefbot.enums.PlayerStatus;
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
public class ReceiveStarterPackStepHandler implements OnboardingStepHandler {

    private static final String NEXT_LABEL = "Далее";
    private static final String PHOTO_PATH = "img/reg/reg3.png";

    private final PlayerService playerService;

    private static final String TEXT = """
            🎁 Стартовые ресурсы
            
            🪵 Древесина ×20
            🪨 Камень ×10
            🎣 Простая удочка
            
            У старого причала удалось найти несколько полезных припасов.
            
            Этого должно хватить, чтобы сделать первые шаги.
            """;

    @Override
    public OnboardingStep getStep() {
        return OnboardingStep.RECEIVE_STARTER_PACK;
    }

    @Override
    public BotResponse process(Player player, String message) {
        if (!NEXT_LABEL.equals(message)) {
            return useKeyboard(KeyboardBuilder.builder().row(NEXT_LABEL).oneTime(true).build());
        }

        player.setOnboardingStep(OnboardingStep.FINISHED);
        player.setStatus(PlayerStatus.ACTIVE);
        playerService.save(player);

        return new BotResponse(
                TEXT,
                PHOTO_PATH,
                null,
                entities(TEXT,
                        e("🎁", "5203996991054432397"),
                        e("🪵", "5188239353045868629"),
                        e("🪨", "5388667686995637270"),
                        e("🎣", "5343609421316521960")
                )
        );
    }
}
