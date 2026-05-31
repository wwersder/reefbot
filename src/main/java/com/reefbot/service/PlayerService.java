package com.reefbot.service;

import java.util.Optional;

import org.springframework.stereotype.Service;

import com.reefbot.entity.Player;
import com.reefbot.repository.PlayerRepository;
import com.reefbot.enums.OnboardingStep;

@Service
public class PlayerService {

    private final PlayerRepository playerRepository;

    public PlayerService(PlayerRepository playerRepository) {
        this.playerRepository = playerRepository;
    }

    public Player getOrCreatePlayer(Long telegramId) {
        Optional<Player> playerOptional = playerRepository.findByTelegramId(telegramId);

        if (playerOptional.isPresent()) {
            return playerOptional.get();
        }

        Player player = Player.builder()
                .telegramId(telegramId)
                .onboardingStep(OnboardingStep.START)
                .build();

        return playerRepository.save(player);
    }

}
