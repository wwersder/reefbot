package com.reefbot.repository;

import com.reefbot.entity.SupportStaff;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SupportStaffRepository extends JpaRepository<SupportStaff, Long> {

    Optional<SupportStaff> findByTelegramId(Long telegramId);

    Optional<SupportStaff> findByTelegramIdAndActiveTrue(Long telegramId);

    Optional<SupportStaff> findByUsernameIgnoreCaseAndActiveTrue(String username);

    List<SupportStaff> findAllByActiveTrue();
}
