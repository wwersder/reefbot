package com.reefbot.service.registration;

import com.reefbot.dto.BotResponse;
import com.reefbot.entity.Player;
import com.reefbot.enums.OnboardingStep;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class OnboardingService {

    private final List<OnboardingStepHandler> handlers;

    private Map<OnboardingStep, OnboardingStepHandler> handlerMap;

    @PostConstruct
    void init() {
        handlerMap = handlers.stream()
                .collect(Collectors.toMap(
                        OnboardingStepHandler::getStep,
                        Function.identity(),
                        (left, right) -> {
                            throw new IllegalStateException("Duplicate handler for step " + left.getStep());
                        },
                        () -> new EnumMap<>(OnboardingStep.class)
                ));
    }

    public BotResponse process(Player player, String message) {
        OnboardingStepHandler handler = handlerMap.get(player.getOnboardingStep());
        if (handler == null) {
            throw new IllegalStateException("No handler registered for step " + player.getOnboardingStep());
        }
        return handler.process(player, message);
    }
}
