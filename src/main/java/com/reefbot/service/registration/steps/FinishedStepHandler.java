package com.reefbot.service.registration.steps;

import com.reefbot.dto.BotResponse;
import com.reefbot.entity.Player;
import com.reefbot.enums.OnboardingStep;
import com.reefbot.enums.PlayerStatus;
import com.reefbot.service.PlayerService;
import com.reefbot.service.registration.OnboardingStepHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class FinishedStepHandler implements OnboardingStepHandler {

    private final PlayerService playerService;

    @Override
    public OnboardingStep getStep() {
        return OnboardingStep.FINISHED;
    }

    @Override
    public BotResponse process(Player player, String message) {
        // safety fallback: players with FINISHED step must have ACTIVE status
        player.setStatus(PlayerStatus.ACTIVE);
        playerService.save(player);
        return null;
    }
}
