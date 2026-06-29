package com.reefbot.repository;

import com.reefbot.entity.PlayerMine;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface PlayerMineRepository extends JpaRepository<PlayerMine, Long> {

    @Query("SELECT pm FROM PlayerMine pm WHERE pm.finishAt IS NOT NULL " +
           "AND pm.finishAt <= :now AND pm.notified = false")
    List<PlayerMine> findMineReady(@Param("now") LocalDateTime now);
}
