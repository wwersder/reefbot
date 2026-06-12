package com.reefbot.repository;

import com.reefbot.entity.Island;
import com.reefbot.entity.IslandBuilding;
import com.reefbot.enums.BuildingType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface IslandBuildingRepository extends JpaRepository<IslandBuilding, Long> {

    Optional<IslandBuilding> findByIslandAndBuildingType(Island island, BuildingType type);

    List<IslandBuilding> findAllByIsland(Island island);

    /** Все здания острова, у которых идёт стройка (buildFinishAt != null). */
    List<IslandBuilding> findAllByIslandAndBuildFinishAtIsNotNull(Island island);
}
