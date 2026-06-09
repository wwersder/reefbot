package com.reefbot.repository;

import com.reefbot.entity.PlayerFishing;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface PlayerFishingRepository extends JpaRepository<PlayerFishing, Long> {

    @Query("SELECT pf FROM PlayerFishing pf WHERE pf.fishingFinishAt IS NOT NULL AND pf.fishingFinishAt <= :now AND pf.fishingNotified = false")
    List<PlayerFishing> findFishingReady(@Param("now") LocalDateTime now);
}
