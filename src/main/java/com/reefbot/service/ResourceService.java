package com.reefbot.service;

import com.reefbot.entity.Island;
import com.reefbot.enums.ResourceType;
import com.reefbot.repository.IslandRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ResourceService {

    private final IslandRepository islandRepository;

    public int getAmount(Island island, ResourceType type) {
        return switch (type) {
            case WOOD   -> island.getWood();
            case STONE  -> island.getStone();
            case FISH   -> island.getFish();
            case SHELLS -> island.getShells();
            case CORAL  -> island.getCoral();
        };
    }

    public void setAmount(Island island, ResourceType type, int amount) {
        switch (type) {
            case WOOD   -> island.setWood(amount);
            case STONE  -> island.setStone(amount);
            case FISH   -> island.setFish(amount);
            case SHELLS -> island.setShells(amount);
            case CORAL  -> island.setCoral(amount);
        }
    }

    public void give(Island island, ResourceType type, int amount) {
        int current = getAmount(island, type);
        int capped = Math.min(current + amount, island.getStorageCapacity());
        setAmount(island, type, capped);
    }

    public void take(Island island, ResourceType type, int amount) {
        int current = getAmount(island, type);
        setAmount(island, type, Math.max(0, current - amount));
    }

    public boolean hasEnough(Island island, ResourceType type, int amount) {
        return getAmount(island, type) >= amount;
    }

    public void giveStarterPack(Island island) {
        island.setWood(20);
        island.setStone(10);
        islandRepository.save(island);
    }

    /** Total resources currently stored (all types summed). */
    public int totalStored(Island island) {
        return island.getWood() + island.getStone() + island.getFish()
                + island.getShells() + island.getCoral();
    }

    /** Storage fill percentage (0–100). */
    public int storageFillPercent(Island island) {
        int cap = island.getStorageCapacity();
        if (cap <= 0) return 0;
        return Math.min(100, totalStored(island) * 100 / cap);
    }
}
