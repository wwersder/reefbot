package com.reefbot.repository;

import com.reefbot.entity.PlinkoLog;
import com.reefbot.entity.Player;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface PlinkoLogRepository extends JpaRepository<PlinkoLog, Long> {

    /**
     * Top 10 single-win records (won - bet, descending).
     * Returns Object[] {PlinkoLog, Player} to avoid N+1 username lookups.
     */
    @Query("""
        SELECT l, p FROM PlinkoLog l
        JOIN Player p ON p.id = l.playerId
        ORDER BY (l.won - l.bet) DESC
        LIMIT 10
        """)
    List<Object[]> findTopByProfitWithPlayer();

    /**
     * Top 10 highest multipliers.
     * Returns Object[] {PlinkoLog, Player} to avoid N+1 username lookups.
     */
    @Query("""
        SELECT l, p FROM PlinkoLog l
        JOIN Player p ON p.id = l.playerId
        ORDER BY l.multiplier DESC
        LIMIT 10
        """)
    List<Object[]> findTopByMultiplierWithPlayer();
}
