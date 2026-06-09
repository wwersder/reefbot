package com.reefbot.service;

import com.reefbot.dto.BotResponse;
import com.reefbot.entity.Player;
import com.reefbot.enums.PlayerStatus;
import com.reefbot.service.game.GameService;
import com.reefbot.service.registration.OnboardingService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
@RequiredArgsConstructor
public class MessageDispatcher {

    private static final Long ADMIN_TELEGRAM_ID = 920215477L;

    private final PlayerService playerService;
    private final AdminService adminService;
    private final OnboardingService onboardingService;
    private final GameService gameService;

    public BotResponse dispatch(Long telegramId, String username, String text, boolean isPrivate) {
        if (ADMIN_TELEGRAM_ID.equals(telegramId)) {
            BotResponse adminResponse = adminService.handle(text);
            if (adminResponse != null) {
                return adminResponse;
            }
        }

        if (isPrivate) {
            Player player = playerService.getOrCreatePlayer(telegramId, username);
            PlayerStatus status = player.getStatus() != null ? player.getStatus() : PlayerStatus.ONBOARDING;
            return switch (status) {
                case ONBOARDING -> onboardingService.process(player, text);
                case ACTIVE     -> gameService.handle(player, text);
            };
        }

        // Group/supergroup: respond only to slash commands
        if (!text.startsWith("/")) {
            return null;
        }

        Optional<Player> playerOpt = playerService.findByTelegramId(telegramId);
        if (playerOpt.isEmpty()) {
            return new BotResponse("Напиши боту в ЛС, чтобы зарегистрироваться.");
        }
        if (playerOpt.get().getStatus() == PlayerStatus.ONBOARDING) {
            return new BotResponse("Заверши регистрацию в ЛС, чтобы играть.");
        }

        return null;
    }
}
