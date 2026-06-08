package com.reefbot.service;

import com.reefbot.entity.Building;
import com.reefbot.entity.Island;
import com.reefbot.enums.BuildingStatus;
import com.reefbot.enums.BuildingType;
import com.reefbot.enums.IslandZone;
import com.reefbot.repository.BuildingRepository;
import com.reefbot.repository.IslandRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class BuildingService {

    private final BuildingRepository buildingRepository;
    private final IslandRepository   islandRepository;
    private final ResourceService    resourceService;

    // ── Read ──────────────────────────────────────────────────────────────────

    public List<Building> getBuiltBuildings(Island island) {
        return buildingRepository.findByIslandAndStatus(island, BuildingStatus.BUILT);
    }

    public List<Building> getInProgressBuildings(Island island) {
        return buildingRepository.findByIslandAndStatus(island, BuildingStatus.IN_PROGRESS);
    }

    public Optional<Building> find(Island island, BuildingType type) {
        return buildingRepository.findByIslandAndType(island, type);
    }

    public boolean isBuilt(Island island, BuildingType type) {
        return find(island, type).map(b -> b.getStatus() == BuildingStatus.BUILT).orElse(false);
    }

    /** Storage capacity based on number of STORAGE buildings built. */
    public int getStorageCapacity(List<Building> builtBuildings) {
        long storageCount = builtBuildings.stream()
                .filter(b -> b.getType() == BuildingType.STORAGE)
                .count();
        return ResourceService.BASE_STORAGE_CAPACITY + (int) storageCount * ResourceService.STORAGE_BUILDING_BONUS;
    }

    /** Sum of development points from all BUILT buildings. */
    public int getDevelopmentPoints(List<Building> builtBuildings) {
        return builtBuildings.stream()
                .mapToInt(b -> b.getType().getDevelopmentPoints())
                .sum();
    }

    // ── Build eligibility ─────────────────────────────────────────────────────

    public enum BuildState { BUILT, IN_PROGRESS, AVAILABLE, LOCKED_RESOURCES, LOCKED_REQUIREMENTS, LOCKED_ZONE }

    public BuildState getBuildState(Island island, BuildingType type,
                                    List<Building> allBuildings, int developmentPoints) {
        Map<BuildingType, BuildingStatus> statusMap = allBuildings.stream()
                .collect(Collectors.toMap(Building::getType, Building::getStatus));

        if (statusMap.getOrDefault(type, null) == BuildingStatus.BUILT)       return BuildState.BUILT;
        if (statusMap.getOrDefault(type, null) == BuildingStatus.IN_PROGRESS) return BuildState.IN_PROGRESS;

        // Zone unlock check
        if (!type.getZone().isUnlocked(developmentPoints)) return BuildState.LOCKED_ZONE;

        // Tier-2 gate: need at least 3 Tier-1 buildings built
        if (type.getTier() == 2) {
            long tier1Built = allBuildings.stream()
                    .filter(b -> b.getStatus() == BuildingStatus.BUILT && b.getType().getTier() == 1)
                    .count();
            if (tier1Built < 3) return BuildState.LOCKED_REQUIREMENTS;
        }

        // Specific prerequisites
        boolean prereqsMet = type.getPrerequisites().stream()
                .allMatch(req -> statusMap.getOrDefault(req, null) == BuildingStatus.BUILT);
        if (!prereqsMet) return BuildState.LOCKED_REQUIREMENTS;

        // Resource check
        if (!resourceService.hasEnough(island, type.getBuildCost())) return BuildState.LOCKED_RESOURCES;

        return BuildState.AVAILABLE;
    }

    // ── Mutations ────────────────────────────────────────────────────────────

    @Transactional
    public Building startBuild(Island island, BuildingType type) {
        resourceService.spend(island, type.getBuildCost());

        LocalDateTime now = LocalDateTime.now();
        Building building = Building.builder()
                .island(island)
                .type(type)
                .status(BuildingStatus.IN_PROGRESS)
                .startedAt(now)
                .finishAt(now.plus(type.getBuildDuration()))
                .build();
        return buildingRepository.save(building);
    }

    /**
     * Marks all finished IN_PROGRESS builds for this island as BUILT.
     * Called from the island screen so the player always sees the latest state.
     * Sets notificationSent=true because the player sees the result directly.
     *
     * @return list of buildings that were just completed
     */
    @Transactional
    public List<Building> completeFinishedBuilds(Island island) {
        LocalDateTime now = LocalDateTime.now();
        List<Building> done = buildingRepository
                .findByIslandAndStatus(island, BuildingStatus.IN_PROGRESS)
                .stream()
                .filter(b -> !b.getFinishAt().isAfter(now))
                .toList();

        done.forEach(b -> {
            b.setStatus(BuildingStatus.BUILT);
            b.setNotificationSent(true);   // user sees it → no push needed
            b.setLastCollectedAt(now);     // start resource clock from now
        });
        buildingRepository.saveAll(done);

        if (!done.isEmpty()) {
            // Bump island level by number of newly completed buildings
            island.setLevel(island.getLevel() + done.size());
            islandRepository.save(island);
        }
        return done;
    }

    /**
     * Collects passive resources from all BUILT buildings and adds them to island storage.
     * Updates lastCollectedAt for each building that produced something.
     */
    @Transactional
    public void collectResources(Island island, List<Building> builtBuildings) {
        int capacity = getStorageCapacity(builtBuildings);
        LocalDateTime now = LocalDateTime.now();
        boolean islandDirty = false;

        for (Building building : builtBuildings) {
            if (building.getType().getProduces() == null) continue;

            LocalDateTime since = building.getLastCollectedAt() != null
                    ? building.getLastCollectedAt()
                    : building.getStartedAt();

            long minutes = ChronoUnit.MINUTES.between(since, now);
            if (minutes <= 0) continue;

            double hours = minutes / 60.0;
            int produced = (int) (building.getType().getProducesAmountPerHour() * hours);
            if (produced <= 0) continue;

            var resType  = building.getType().getProduces();
            int current  = resourceService.getAmount(island, resType);
            int canAdd   = capacity - current;
            if (canAdd <= 0) continue;   // storage full for this resource

            int toAdd = Math.min(produced, canAdd);
            resourceService.add(island, resType, toAdd, capacity);
            building.setLastCollectedAt(now);
            islandDirty = true;
        }

        buildingRepository.saveAll(builtBuildings);
        if (islandDirty) islandRepository.save(island);
    }
}
