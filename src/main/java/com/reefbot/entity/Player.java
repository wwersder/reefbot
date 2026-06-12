package com.reefbot.entity;

import com.reefbot.enums.OnboardingStep;
import com.reefbot.enums.PlayerStatus;
import jakarta.persistence.*;
import lombok.*;

import java.util.ArrayList;
import java.util.List;

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

    // ── Relations ────────────────────────────────────────────────────────────

    @OneToOne(mappedBy = "player", cascade = CascadeType.ALL,
              fetch = FetchType.EAGER, orphanRemoval = true)
    private PlayerState state;

    @OneToOne(mappedBy = "player", cascade = CascadeType.ALL,
              fetch = FetchType.EAGER, orphanRemoval = true)
    private PlayerFishing fishing;

    @OneToOne(mappedBy = "player", cascade = CascadeType.ALL,
              fetch = FetchType.EAGER, orphanRemoval = true)
    private PlayerTide tide;

    @OneToMany(mappedBy = "player", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<InventoryItem> inventory = new ArrayList<>();

    @OneToOne(mappedBy = "player")
    private Island island;
}
