package com.reefbot.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.reefbot.entity.Island;

public interface IslandRepository extends JpaRepository<Island, Long> {

}