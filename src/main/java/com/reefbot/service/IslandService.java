package com.reefbot.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import com.reefbot.entity.Island;
import com.reefbot.entity.Player;
import com.reefbot.repository.IslandRepository;

@Service
@RequiredArgsConstructor
public class IslandService {

    private final IslandRepository islandRepository;

    public Island createIsland(Player player, String name) {
        Island island = Island.builder()
                .name(name)
                .level(0)
                .player(player)
                .build();

        Island savedIsland = islandRepository.save(island);
        player.setIsland(savedIsland);
        return savedIsland;
    }

    public Island renameIsland(Island island, String name) {
        island.setName(name);
        return islandRepository.save(island);
    }
}
