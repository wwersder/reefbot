package com.reefbot.entity;

import com.reefbot.enums.OnboardingStep;
import com.reefbot.enums.PlayerStatus;
import jakarta.persistence.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
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

    /**
     * Версия для оптимистичной блокировки.
     * Если два потока одновременно загрузили и изменили одного игрока,
     * второй save() бросит ObjectOptimisticLockingFailureException.
     * MessageDispatcher перехватывает это и повторяет запрос с актуальными данными.
     */
    @Version
    private Long version;

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

    /** When true, player cannot create new support tickets. */
    @Builder.Default
    private boolean supportBlocked = false;

    // ── Plinko state ─────────────────────────────────────────────────────────

    /** Shells lost today (resets daily). */
    @Builder.Default
    private Integer plinkoDailyLost = 0;

    /** The date plinkoDailyLost was last accumulated (null = never played). */
    private LocalDate plinkoDailyDate;

    /** Timestamp of the last plinko throw (for cooldown enforcement). */
    private LocalDateTime plinkoLastPlay;
}
