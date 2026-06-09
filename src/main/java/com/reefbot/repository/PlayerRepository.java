package com.reefbot.repository;

import com.reefbot.entity.Player;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PlayerRepository extends JpaRepository<Player, Long> {

    Optional<Player> findByTelegramId(Long telegramId);
}