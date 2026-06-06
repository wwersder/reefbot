package com.reefbot.service.registration;

import com.reefbot.dto.BotResponse;
import com.reefbot.entity.Player;
import com.reefbot.enums.OnboardingStep;
import com.reefbot.service.PlayerService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class OnboardingService {

    private final PlayerService playerService;

    private final List<OnboardingStepHandler> handlers;

    private Map<OnboardingStep, OnboardingStepHandler> handlerMap;

    @PostConstruct
    void init() {
        handlerMap = handlers.stream().collect(Collectors.toMap(OnboardingStepHandler::getStep, Function.identity()));
    }

    public BotResponse processMessage(Long telegramId, String message) {
        Player player = playerService.getOrCreatePlayer(telegramId);

        if (player.getOnboardingStep() == null) {
            player.setOnboardingStep(OnboardingStep.WELCOME);
            playerService.save(player);
        }

        OnboardingStepHandler handler = handlerMap.get(player.getOnboardingStep());

        return handler.process(player, message);
    }
}
