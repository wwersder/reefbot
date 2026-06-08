package com.reefbot.service;

import java.util.Optional;

import com.reefbot.enums.OnboardingStep;
import com.reefbot.enums.PlayerStatus;
import com.reefbot.repository.IslandRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.reefbot.entity.Player;
import com.reefbot.repository.PlayerRepository;

@Service
@RequiredArgsConstructor
public class PlayerService {

    private final PlayerRepository playerRepository;
    private final IslandRepository islandRepository;

    public Optional<Player> findByTelegramId(Long telegramId) {
        return playerRepository.findByTelegramId(telegramId);
    }

    public Player getOrCreatePlayer(Long telegramId, String username) {
        Optional<Player> playerOptional = playerRepository.findByTelegramId(telegramId);

        if (playerOptional.isPresent()) {
            Player player = playerOptional.get();
            if (username != null && !username.equals(player.getUsername())) {
                player.setUsername(username);
                return playerRepository.save(player);
            }
            return player;
        }

        Player player = Player.builder()
                .telegramId(telegramId)
                .username(username)
                .onboardingStep(OnboardingStep.WELCOME)
                .status(PlayerStatus.ONBOARDING)
                .build();

        return playerRepository.save(player);
    }

    public Player save(Player player) {
        return playerRepository.save(player);
    }

    @Transactional
    public boolean deletePlayer(Long playerId) {
        return playerRepository.findById(playerId).map(player -> {
            if (player.getIsland() != null) {
                islandRepository.delete(player.getIsland());
            }
            playerRepository.delete(player);
            return true;
        }).orElse(false);
    }

}
