package com.reefbot.service.game;

import com.reefbot.entity.Island;
import com.reefbot.entity.IslandBuilding;
import com.reefbot.enums.BuildingType;
import com.reefbot.repository.IslandBuildingRepository;
import com.reefbot.repository.IslandRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Бизнес-логика зданий.
 *
 * <p>Жизненный цикл записи {@code IslandBuilding}:
 * <pre>
 *  нет записи
 *    → startBuild()  → level=0, buildFinishAt=T
 *    → finalize()    → level=1, buildFinishAt=null, productionCollectedAt=now
 *    → (работает)    → collectFish() обнуляет таймер сбора
 *    → startBuild()  → level=1, buildFinishAt=T  (апгрейд; level НЕ меняется до finalize)
 *    → finalize()    → level=2, buildFinishAt=null
 *    ...
 * </pre>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BuildingService {

    private final IslandBuildingRepository buildingRepo;
    private final IslandRepository islandRepository;

    // ── Queries ────────────────────────────────────────────────────────────

    public Optional<IslandBuilding> find(Island island, BuildingType type) {
        return buildingRepo.findByIslandAndBuildingType(island, type);
    }

    /** True если игрок может позволить себе постройку/апгрейд до nextLevel. */
    public boolean canAfford(Island island, BuildingType type, int targetLevel) {
        return island.getFish()   >= type.fishCostFor(targetLevel)
            && island.getShells() >= type.shellsCostFor(targetLevel)
            && island.getWood()   >= type.woodCostFor(targetLevel);
    }

    public boolean canAffordStorageUpgrade(Island island, BuildingType type, int targetStorageLevel) {
        return island.getFish() >= type.storageFishCostFor(targetStorageLevel);
    }

    /** True — идёт строительство, ещё не завершилось. */
    public boolean isUnderConstruction(IslandBuilding b) {
        return b.getBuildFinishAt() != null && LocalDateTime.now().isBefore(b.getBuildFinishAt());
    }

    /** True — строительство завершено, нужен вызов {@link #finalize(IslandBuilding)}. */
    public boolean isConstructionReady(IslandBuilding b) {
        return b.getBuildFinishAt() != null && !LocalDateTime.now().isBefore(b.getBuildFinishAt());
    }

    /** True — здание работает (не строится). */
    public boolean isOperational(IslandBuilding b) {
        return b.getBuildFinishAt() == null && b.getLevel() > 0;
    }

    /** True — хранилище расширяется прямо сейчас. */
    public boolean isStorageUnderConstruction(IslandBuilding b) {
        return b.getStorageBuildFinishAt() != null
                && LocalDateTime.now().isBefore(b.getStorageBuildFinishAt());
    }

    /** True — расширение хранилища завершено, нужен finalize. */
    public boolean isStorageConstructionReady(IslandBuilding b) {
        return b.getStorageBuildFinishAt() != null
                && !LocalDateTime.now().isBefore(b.getStorageBuildFinishAt());
    }

    // ── Mutations ──────────────────────────────────────────────────────────

    /**
     * Запустить постройку/апгрейд. Ресурсы списываются немедленно.
     *
     * @throws IllegalStateException если игрок уже строит это здание
     * @throws IllegalArgumentException если не хватает ресурсов
     */
    @Transactional
    public IslandBuilding startBuild(Island island, BuildingType type) {
        Optional<IslandBuilding> existing = buildingRepo.findByIslandAndBuildingType(island, type);

        if (existing.isPresent() && existing.get().getBuildFinishAt() != null) {
            throw new IllegalStateException("Здание уже строится");
        }

        int currentLevel = existing.map(IslandBuilding::getLevel).orElse(0);
        int targetLevel  = currentLevel + 1;

        if (!canAfford(island, type, targetLevel)) {
            throw new IllegalArgumentException("Недостаточно ресурсов");
        }

        // Deduct costs
        island.setFish(island.getFish()     - type.fishCostFor(targetLevel));
        island.setShells(island.getShells() - type.shellsCostFor(targetLevel));
        island.setWood(island.getWood()     - type.woodCostFor(targetLevel));
        islandRepository.save(island);

        LocalDateTime finish = LocalDateTime.now().plusMinutes(type.buildMinutesFor(targetLevel));

        IslandBuilding building = existing.orElseGet(() ->
            IslandBuilding.builder()
                .island(island)
                .buildingType(type)
                .level(0)
                .build()
        );
        building.setBuildFinishAt(finish);
        return buildingRepo.save(building);
    }

    /**
     * Завершить строительство: увеличить уровень, обнулить таймер.
     * Должен вызываться только когда {@link #isConstructionReady(IslandBuilding)} == true.
     */
    @Transactional
    public IslandBuilding finalize(IslandBuilding building) {
        building.setLevel(building.getLevel() + 1);
        building.setBuildFinishAt(null);
        // Начинаем копить производство с момента финализации
        building.setProductionCollectedAt(LocalDateTime.now());
        return buildingRepo.save(building);
    }

    // ── Production ─────────────────────────────────────────────────────────

    /**
     * Сколько рыбы накоплено с момента последнего сбора (или с момента постройки).
     * Возвращает 0 если здание не введено в эксплуатацию.
     */
    public int getAccumulatedFish(IslandBuilding building) {
        if (!isOperational(building)) return 0;

        LocalDateTime from = building.getProductionCollectedAt();
        if (from == null) {
            // Не должно быть — при finalize() ставим productionCollectedAt.
            // Fallback: считаем с CAP_HOURS назад (максимум).
            from = LocalDateTime.now().minusHours(BuildingType.CAP_HOURS);
        }

        double elapsedHours = Duration.between(from, LocalDateTime.now()).toMinutes() / 60.0;
        int produced = (int)(building.getBuildingType().productionPerHourAt(building.getLevel()) * elapsedHours);
        int storageLvl = building.getStorageLevel() != null ? building.getStorageLevel() : 1;
        return Math.min(produced, building.getBuildingType().storageCapAt(storageLvl));
    }

    /**
     * Собрать накопленную рыбу, добавить к острову. Возвращает количество собранной рыбы.
     */
    @Transactional
    public int collectFish(Island island, IslandBuilding building) {
        int fish = getAccumulatedFish(building);
        if (fish > 0) {
            island.setFish(island.getFish() + fish);
            islandRepository.save(island);
        }
        building.setProductionCollectedAt(LocalDateTime.now());
        buildingRepo.save(building);
        return fish;
    }

    // ── Storage upgrade ────────────────────────────────────────────────────

    /**
     * Запустить улучшение хранилища. Списывает рыбу немедленно.
     */
    @Transactional
    public IslandBuilding startStorageBuild(Island island, IslandBuilding building) {
        BuildingType type = building.getBuildingType();
        int targetStorageLevel = (building.getStorageLevel() != null ? building.getStorageLevel() : 1) + 1;

        if (!canAffordStorageUpgrade(island, type, targetStorageLevel)) {
            throw new IllegalArgumentException("Недостаточно рыбы для расширения хранилища");
        }

        island.setFish(island.getFish() - type.storageFishCostFor(targetStorageLevel));
        islandRepository.save(island);

        int minutes = type.storageBuildMinutesFor(targetStorageLevel);
        building.setStorageBuildFinishAt(
                minutes > 0 ? LocalDateTime.now().plusMinutes(minutes) : null
        );
        // Instant upgrade (0 min) — finalize immediately
        if (minutes == 0) {
            building.setStorageLevel(targetStorageLevel);
            building.setStorageBuildFinishAt(null);
        }
        return buildingRepo.save(building);
    }

    /**
     * Завершить улучшение хранилища: увеличить storageLevel, обнулить таймер.
     */
    @Transactional
    public IslandBuilding finalizeStorage(IslandBuilding building) {
        int current = building.getStorageLevel() != null ? building.getStorageLevel() : 1;
        building.setStorageLevel(current + 1);
        building.setStorageBuildFinishAt(null);
        return buildingRepo.save(building);
    }
}
