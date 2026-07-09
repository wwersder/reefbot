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

    /**
     * Finds tides that expired without a next tide being scheduled.
     * Detected by: tideExpiresAt in the past AND tideAvailableAt in the past.
     * If scheduleNextTide() was called, tideAvailableAt would be 5–10 h in the future.
     */
    @Query("""
        SELECT pt FROM PlayerTide pt
        WHERE pt.tideExpiresAt IS NOT NULL
          AND pt.tideExpiresAt   < :now
          AND pt.tideAvailableAt < :now
        """)
    List<PlayerTide> findExpiredWithoutNextTide(@Param("now") LocalDateTime now);
}
