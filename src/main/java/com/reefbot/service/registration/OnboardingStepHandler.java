package com.reefbot.service.registration;

import com.reefbot.dto.BotResponse;
import com.reefbot.entity.Player;
import com.reefbot.enums.OnboardingStep;

public interface OnboardingStepHandler {
    OnboardingStep getStep();

    BotResponse process(Player player, String message);
}
