package com.reefbot.service.registration;

import com.reefbot.dto.BotResponse;
import com.reefbot.entity.Player;
import com.reefbot.enums.OnboardingStep;
import com.reefbot.util.KeyboardBuilder;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.MessageEntity;

import java.util.List;

@Component
public class WelcomeStepHandler implements OnboardingStepHandler {

    @Override
    public OnboardingStep getStep() {
        return OnboardingStep.WELCOME;
    }

    @Override
    public BotResponse process(Player player, String message) {
        String text = "🌴 Где-то среди бескрайнего архипелага находится небольшой остров.\n\nНа картах он ничем не примечателен.\nНа нём нет богатств и величественных построек.\n\nПока что.\n\n⚡️ С этого дня остров принадлежит тебе.";

        List<MessageEntity> entities = List.of(
                MessageEntity.builder()
                        .type("custom_emoji")
                        .offset(0)
                        .length(2)
                        .customEmojiId("5807538646030489502")
                        .build(),
                MessageEntity.builder()
                        .type("custom_emoji")
                        .offset(text.indexOf("⚡️"))
                        .length(2)
                        .customEmojiId("5807485199457458084")
                        .build()
        );

        return new BotResponse(
                text,
                "/Users/akhivyk/Documents/my/reefbot/img/reg/reg1.png",
                KeyboardBuilder.builder().row("Продолжить").oneTime(true).build(),
                entities
        );
    }
}
