package com.reefbot.repository;

import com.reefbot.entity.Player;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.reefbot.entity.Island;

import java.util.Optional;

public interface IslandRepository extends JpaRepository<Island, Long> {

    Optional<Island> findByPlayer(Player player);

    /** Acquires a row-level write lock — use inside @Transactional play() to prevent race conditions. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT i FROM Island i WHERE i.player = :player")
    Optional<Island> findByPlayerForUpdate(@Param("player") Player player);
}