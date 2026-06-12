package com.reefbot.service;

import com.reefbot.entity.Player;
import com.reefbot.entity.PlayerFishing;
import com.reefbot.entity.PlayerState;
import com.reefbot.entity.PlayerTide;
import com.reefbot.enums.OnboardingStep;
import com.reefbot.enums.PlayerScreen;
import com.reefbot.enums.PlayerStatus;
import com.reefbot.repository.IslandRepository;
import com.reefbot.repository.PlayerRepository;
import com.reefbot.service.game.TideService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
@RequiredArgsConstructor
public class PlayerService {

    private final PlayerRepository playerRepository;
    private final IslandRepository islandRepository;
    private final TideService tideService;

    public Optional<Player> findByTelegramId(Long telegramId) {
        return playerRepository.findByTelegramId(telegramId);
    }

    @Transactional
    public Player getOrCreatePlayer(Long telegramId, String username) {
        Optional<Player> existing = playerRepository.findByTelegramId(telegramId);

        if (existing.isPresent()) {
            Player player = existing.get();
            if (username != null && !username.equals(player.getUsername())) {
                player.setUsername(username);
                return playerRepository.save(player);
            }
            return player;
        }

        // Build state, fishing, tide without IDs — cascade will persist them
        PlayerState state = PlayerState.builder()
                .currentScreen(PlayerScreen.MAIN)
                .hasCompletedFirstFish(false)
                .build();

        PlayerFishing fishing = PlayerFishing.builder()
                .fishingXp(0)
                .fishingLevel(1)
                .fishingNotified(false)
                .build();

        PlayerTide tide = PlayerTide.builder().build();

        Player player = Player.builder()
                .telegramId(telegramId)
                .username(username)
                .onboardingStep(OnboardingStep.WELCOME)
                .status(PlayerStatus.ONBOARDING)
                .state(state)
                .fishing(fishing)
                .tide(tide)
                .build();

        // Wire back-references so cascade FK is set correctly
        state.setPlayer(player);
        fishing.setPlayer(player);
        tide.setPlayer(player);

        Player saved = playerRepository.save(player);

        // Schedule first tide (1–2h from now)
        tideService.scheduleFirstTide(saved);

        return saved;
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
            // state, fishing, inventory deleted via cascade + orphanRemoval
            playerRepository.delete(player);
            return true;
        }).orElse(false);
    }
}
