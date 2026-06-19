package com.reefbot.repository;

import com.reefbot.entity.PlinkoLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface PlinkoLogRepository extends JpaRepository<PlinkoLog, Long> {

    /** Top 10 single-win records (won - bet, descending). */
    @Query("""
        SELECT l FROM PlinkoLog l
        ORDER BY (l.won - l.bet) DESC
        LIMIT 10
        """)
    List<PlinkoLog> findTopByProfit();

    /** Top 10 highest multipliers. */
    @Query("""
        SELECT l FROM PlinkoLog l
        ORDER BY l.multiplier DESC
        LIMIT 10
        """)
    List<PlinkoLog> findTopByMultiplier();
}
