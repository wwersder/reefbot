package com.reefbot.entity;

import com.reefbot.enums.OnboardingStep;
import com.reefbot.enums.PlayerScreen;
import com.reefbot.enums.PlayerStatus;
import jakarta.persistence.*;
import lombok.*;

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

    @OneToOne(mappedBy = "player")
    private Island island;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    private PlayerScreen screen = PlayerScreen.MAIN;

    private String pendingAction;

}
