package com.reefbot.service;

import com.reefbot.dto.BotResponse;
import com.reefbot.entity.Player;
import com.reefbot.enums.PlayerStatus;
import com.reefbot.service.game.GameService;
import com.reefbot.service.registration.OnboardingService;
import com.reefbot.service.support.SupportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class MessageDispatcher {

    private static final Long ADMIN_TELEGRAM_ID = 920215477L;

    /**
     * Сколько раз повторить запрос при конфликте оптимистичной блокировки.
     * На практике при нормальной работе повтор нужен крайне редко:
     * Telegram доставляет апдейты последовательно для одного пользователя,
     * но дублирование возможно при сетевых ретраях со стороны Telegram.
     */
    private static final int MAX_RETRIES = 3;

    private final PlayerService playerService;
    private final AdminService adminService;
    private final OnboardingService onboardingService;
    private final GameService gameService;
    private final SupportService supportService;

    public BotResponse dispatch(Long telegramId, String username, String text, boolean isPrivate, Long chatId) {
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                return doDispatch(telegramId, username, text, isPrivate, chatId);
            } catch (ObjectOptimisticLockingFailureException e) {
                if (attempt == MAX_RETRIES) {
                    log.error("Optimistic lock conflict for player {} after {} attempts, giving up",
                            telegramId, MAX_RETRIES, e);
                    return new BotResponse("Произошла ошибка. Попробуй ещё раз.");
                }
                log.warn("Optimistic lock conflict for player {}, retry {}/{}", telegramId, attempt, MAX_RETRIES);
                // Пауза перед повтором — даём другому потоку завершить запись
                try { Thread.sleep(50L * attempt); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
        }
        return null; // unreachable
    }

    private BotResponse doDispatch(Long telegramId, String username, String text, boolean isPrivate, Long chatId) {
        if (ADMIN_TELEGRAM_ID.equals(telegramId)) {
            BotResponse adminResponse = adminService.handle(text, chatId);
            if (adminResponse != null) {
                return adminResponse;
            }
        }

        if (isPrivate) {
            Player player = playerService.getOrCreatePlayer(telegramId, username);
            PlayerStatus status = player.getStatus() != null ? player.getStatus() : PlayerStatus.ONBOARDING;

            // Support commands available from any screen and any status.
            // /support <text> also acts as a follow-up relay when a ticket is already open.
            if (isSupportCommand(text)) {
                return supportService.handlePlayerCommand(player, text);
            }

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

    private boolean isSupportCommand(String text) {
        return text.startsWith("/support")
            || "/myticket".equals(text)
            || "/closeticket".equals(text);
    }
}
