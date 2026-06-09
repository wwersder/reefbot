package com.reefbot.repository;

import com.reefbot.entity.Player;
import com.reefbot.enums.PlayerScreen;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface PlayerRepository extends JpaRepository<Player, Long> {

    Optional<Player> findByTelegramId(Long telegramId);

    @Query("SELECT p FROM Player p WHERE p.fishingFinishAt IS NOT NULL AND p.fishingFinishAt <= :now AND p.currentScreen = :screen")
    List<Player> findFishingReady(@Param("now") LocalDateTime now, @Param("screen") PlayerScreen screen);
}