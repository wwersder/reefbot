package com.reefbot.repository;

import com.reefbot.entity.Building;
import com.reefbot.entity.Island;
import com.reefbot.enums.BuildingStatus;
import com.reefbot.enums.BuildingType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface BuildingRepository extends JpaRepository<Building, Long> {

    List<Building> findByIsland(Island island);

    List<Building> findByIslandAndStatus(Island island, BuildingStatus status);

    Optional<Building> findByIslandAndType(Island island, BuildingType type);

    boolean existsByIslandAndType(Island island, BuildingType type);

    /** For the scheduler: IN_PROGRESS buildings whose timer has expired. */
    List<Building> findByStatusAndFinishAtBefore(BuildingStatus status, LocalDateTime cutoff);

    /**
     * For the scheduler: BUILT buildings whose completion notification
     * has not yet been sent. Fetches island→player to avoid N+1.
     */
    @Query("SELECT b FROM Building b JOIN FETCH b.island i JOIN FETCH i.player WHERE b.status = 'BUILT' AND b.notificationSent = false")
    List<Building> findBuiltPendingNotification();
}
