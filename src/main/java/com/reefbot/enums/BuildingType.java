package com.reefbot.enums;

import lombok.Getter;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

@Getter
public enum BuildingType {

    // ── Tier 1 ────────────────────────────────────────────────────────────────

    WOODCUTTER_HUT(
            "🪓 Лесорубная хижина",
            1, IslandZone.FOREST,
            Map.of(ResourceType.WOOD, 15, ResourceType.STONE, 5),
            Duration.ofMinutes(30),
            2,
            "+8 🪵/час",
            ResourceType.WOOD, 8,
            new String[]{}
    ),
    FISHING_PLATFORM(
            "🎣 Рыбацкий помост",
            1, IslandZone.COASTAL,
            Map.of(ResourceType.WOOD, 10, ResourceType.STONE, 5),
            Duration.ofMinutes(15),
            2,
            "+6 🐟/час",
            ResourceType.FISH, 6,
            new String[]{}
    ),
    STORAGE(
            "📦 Склад",
            1, IslandZone.SETTLEMENT,
            Map.of(ResourceType.WOOD, 20, ResourceType.STONE, 10),
            Duration.ofMinutes(45),
            2,
            "+150 к ёмкости склада",
            null, 0,
            new String[]{}
    ),

    // ── Tier 2 ────────────────────────────────────────────────────────────────

    QUARRY(
            "⛏ Каменоломня",
            2, IslandZone.HILL,
            Map.of(ResourceType.WOOD, 25, ResourceType.STONE, 15),
            Duration.ofHours(1),
            3,
            "+5 🪨/час",
            ResourceType.STONE, 5,
            new String[]{}
    ),
    SAWMILL(
            "🪚 Лесопилка",
            2, IslandZone.FOREST,
            Map.of(ResourceType.WOOD, 40, ResourceType.STONE, 20),
            Duration.ofHours(2),
            4,
            "+3 🪚/час (перерабатывает древесину)",
            null, 0,
            new String[]{"WOODCUTTER_HUT"}
    ),
    GARDEN(
            "🌿 Огород",
            2, IslandZone.SETTLEMENT,
            Map.of(ResourceType.WOOD, 20, ResourceType.STONE, 10),
            Duration.ofMinutes(90),
            3,
            "+4 🍞/час",
            null, 0,
            new String[]{}
    ),
    FISHING_DOCK(
            "🛶 Рыбацкая пристань",
            2, IslandZone.COASTAL,
            Map.of(ResourceType.WOOD, 30, ResourceType.STONE, 20, ResourceType.FISH, 10),
            Duration.ofHours(2),
            5,
            "+12 🐟/час",
            ResourceType.FISH, 12,
            new String[]{"FISHING_PLATFORM"}
    ),
    MARKETPLACE(
            "🏪 Рыночная лавка",
            2, IslandZone.SETTLEMENT,
            Map.of(ResourceType.WOOD, 35, ResourceType.STONE, 15, ResourceType.FISH, 10),
            Duration.ofMinutes(90),
            4,
            "Торговля с купцами",
            null, 0,
            new String[]{}
    );

    // ── Fields ────────────────────────────────────────────────────────────────

    private final String displayName;
    private final int tier;
    private final IslandZone zone;
    private final Map<ResourceType, Integer> buildCost;
    private final Duration buildDuration;
    private final int developmentPoints;
    private final String effectDescription;

    /** Resource this building produces passively, or null if none. */
    private final ResourceType produces;
    /** Units produced per hour (0 if produces == null). */
    private final int producesAmountPerHour;

    /** Names of BuildingType values that must be BUILT before this one can start. */
    private final String[] prerequisiteNames;

    BuildingType(
            String displayName,
            int tier,
            IslandZone zone,
            Map<ResourceType, Integer> buildCost,
            Duration buildDuration,
            int developmentPoints,
            String effectDescription,
            ResourceType produces,
            int producesAmountPerHour,
            String[] prerequisiteNames
    ) {
        this.displayName = displayName;
        this.tier = tier;
        this.zone = zone;
        this.buildCost = buildCost;
        this.buildDuration = buildDuration;
        this.developmentPoints = developmentPoints;
        this.effectDescription = effectDescription;
        this.produces = produces;
        this.producesAmountPerHour = producesAmountPerHour;
        this.prerequisiteNames = prerequisiteNames;
    }

    public List<BuildingType> getPrerequisites() {
        return Arrays.stream(prerequisiteNames)
                .map(BuildingType::valueOf)
                .toList();
    }

    /** Find by display name (used for button text → enum lookup). */
    public static java.util.Optional<BuildingType> fromDisplayName(String name) {
        return Arrays.stream(values())
                .filter(b -> b.displayName.equals(name))
                .findFirst();
    }

    /** All building types belonging to a given zone. */
    public static List<BuildingType> inZone(IslandZone zone) {
        return Arrays.stream(values())
                .filter(b -> b.zone == zone)
                .toList();
    }
}
