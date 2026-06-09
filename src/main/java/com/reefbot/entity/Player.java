package com.reefbot.entity;

import com.reefbot.enums.FishingSpot;
import com.reefbot.enums.OnboardingStep;
import com.reefbot.enums.PlayerScreen;
import com.reefbot.enums.PlayerStatus;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "players")
public class Player {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long telegramId;

    private String username;

    @Enumerated(EnumType.STRING)
    private OnboardingStep onboardingStep;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    private PlayerStatus status = PlayerStatus.ONBOARDING;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    private PlayerScreen currentScreen = PlayerScreen.MAIN;

    // Fishing
    @Builder.Default
    private Integer fishingXp = 0;

    @Builder.Default
    private Integer fishingLevel = 1;

    private LocalDateTime fishingFinishAt;

    @Enumerated(EnumType.STRING)
    private FishingSpot fishingSpot;

    @Builder.Default
    private Boolean hasCompletedFirstFish = false;

    /** True after scheduler sends the fishing-done notification. Prevents duplicate sends. */
    @Builder.Default
    private Boolean fishingNotified = false;

    @OneToOne(mappedBy = "player")
    private Island island;
}
