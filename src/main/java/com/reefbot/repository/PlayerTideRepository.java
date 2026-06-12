package com.reefbot.repository;

import com.reefbot.entity.PlayerTide;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface PlayerTideRepository extends JpaRepository<PlayerTide, Long> {

    @Query("""
        SELECT pt FROM PlayerTide pt
        WHERE pt.tideAvailableAt <= :now
          AND pt.tideExpiresAt   >  :now
          AND pt.tideNotified    = false
        """)
    List<PlayerTide> findActiveTidesNotNotified(@Param("now") LocalDateTime now);

    List<PlayerTide> findByTideAvailableAtIsNull();
}
