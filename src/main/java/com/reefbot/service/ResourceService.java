package com.reefbot.service;

import com.reefbot.entity.Island;
import com.reefbot.enums.ResourceType;
import com.reefbot.repository.IslandRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ResourceService {

    public static final int BASE_STORAGE_CAPACITY = 200;
    public static final int STORAGE_BUILDING_BONUS = 150;

    private final IslandRepository islandRepository;

    // ── Queries ───────────────────────────────────────────────────────────────

    public int getAmount(Island island, ResourceType type) {
        return switch (type) {
            case WOOD   -> island.getWood();
            case STONE  -> island.getStone();
            case FISH   -> island.getFish();
            case SHELLS -> island.getShells();
            case CORAL  -> island.getCoral();
        };
    }

    public boolean hasEnough(Island island, Map<ResourceType, Integer> cost) {
        return cost.entrySet().stream()
                .allMatch(e -> getAmount(island, e.getKey()) >= e.getValue());
    }

    // ── Mutations ────────────────────────────────────────────────────────────

    @Transactional
    public void giveStarterPack(Island island) {
        island.setWood(20);
        island.setStone(10);
        islandRepository.save(island);
    }

    @Transactional
    public void spend(Island island, Map<ResourceType, Integer> cost) {
        cost.forEach((type, amount) -> setAmount(island, type,
                Math.max(0, getAmount(island, type) - amount)));
        islandRepository.save(island);
    }

    @Transactional
    public void add(Island island, ResourceType type, int amount, int storageCapacity) {
        int current = getAmount(island, type);
        int updated = Math.min(current + amount, storageCapacity);
        setAmount(island, type, updated);
        islandRepository.save(island);
    }

    // ── Formatting ───────────────────────────────────────────────────────────

    /** "🪵 Дерево: 42 / 200" */
    public static String formatResource(String emoji, String name, int amount, int capacity) {
        return emoji + " " + name + ": " + amount + " / " + capacity;
    }

    /** "🪵15, 🪨5" */
    public String formatCost(Map<ResourceType, Integer> cost) {
        return cost.entrySet().stream()
                .map(e -> e.getKey().getEmoji() + e.getValue())
                .collect(Collectors.joining(", "));
    }

    /** Multiline resource summary for the island screen. */
    public String formatInventory(Island island, int storageCapacity) {
        return String.format(
                "%s Древесина: %d / %d\n%s Камень: %d / %d\n%s Рыба: %d / %d\n%s Ракушки: %d\n%s Коралл: %d",
                ResourceType.WOOD.getEmoji(),   island.getWood(),   storageCapacity,
                ResourceType.STONE.getEmoji(),  island.getStone(),  storageCapacity,
                ResourceType.FISH.getEmoji(),   island.getFish(),   storageCapacity,
                ResourceType.SHELLS.getEmoji(), island.getShells(),
                ResourceType.CORAL.getEmoji(),  island.getCoral()
        );
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private void setAmount(Island island, ResourceType type, int amount) {
        switch (type) {
            case WOOD   -> island.setWood(amount);
            case STONE  -> island.setStone(amount);
            case FISH   -> island.setFish(amount);
            case SHELLS -> island.setShells(amount);
            case CORAL  -> island.setCoral(amount);
        }
    }
}
