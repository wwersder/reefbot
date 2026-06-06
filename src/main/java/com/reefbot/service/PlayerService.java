package com.reefbot.service;

import java.util.Optional;

import com.reefbot.enums.OnboardingStep;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import com.reefbot.entity.Player;
import com.reefbot.repository.PlayerRepository;

@Service
@RequiredArgsConstructor
public class PlayerService {

    private final PlayerRepository playerRepository;

    public Player getOrCreatePlayer(Long telegramId) {
        Optional<Player> playerOptional = playerRepository.findByTelegramId(telegramId);

        if (playerOptional.isPresent()) {
            return playerOptional.get();
        }

        Player player = Player.builder()
                .telegramId(telegramId)
                .onboardingStep(OnboardingStep.WELCOME)
                .build();

        return playerRepository.save(player);
    }

    public Player save(Player player) {
        return playerRepository.save(player);
    }

}
