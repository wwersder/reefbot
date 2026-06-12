package com.reefbot.repository;

import com.reefbot.entity.Player;
import org.springframework.data.jpa.repository.JpaRepository;

import com.reefbot.entity.Island;

import java.util.Optional;

public interface IslandRepository extends JpaRepository<Island, Long> {

    Optional<Island> findByPlayer(Player player);
}