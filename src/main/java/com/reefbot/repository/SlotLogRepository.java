package com.reefbot.repository;

import com.reefbot.entity.SlotLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface SlotLogRepository extends JpaRepository<SlotLog, Long> {}
