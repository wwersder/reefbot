package com.reefbot.service;

import com.reefbot.dto.BotResponse;
import com.reefbot.entity.Player;
import com.reefbot.service.registration.OnboardingService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class MessageDispatcher {

    private static final Long ADMIN_TELEGRAM_ID = 920215477L;

    private final PlayerService playerService;
    private final AdminService adminService;
    private final OnboardingService onboardingService;

    public BotResponse dispatch(Long telegramId, String username, String text) {
        if (ADMIN_TELEGRAM_ID.equals(telegramId)) {
            BotResponse adminResponse = adminService.handle(text);
            if (adminResponse != null) {
                return adminResponse;
            }
        }

        Player player = playerService.getOrCreatePlayer(telegramId, username);

        return switch (player.getStatus()) {
            case ONBOARDING -> onboardingService.process(player, text);
            case ACTIVE -> null; // TODO: game handler
        };
    }
}
