package com.reefbot.repository;

import com.reefbot.entity.PlayerForest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface PlayerForestRepository extends JpaRepository<PlayerForest, Long> {

    @Query("SELECT pf FROM PlayerForest pf WHERE pf.finishAt IS NOT NULL " +
           "AND pf.finishAt <= :now AND pf.notified = false")
    List<PlayerForest> findForestReady(@Param("now") LocalDateTime now);
}
