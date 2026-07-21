package com.reefbot.entity;

import com.reefbot.enums.SlotWarMode;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "slot_war_logs")
public class SlotWarLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "player_id", nullable = false)
    private Player player;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SlotWarMode mode;

    @Column(nullable = false)
    private Integer bet;

    @Column(name = "payline_win", nullable = false)
    private Integer paylineWin;

    @Column(name = "total_mult", nullable = false)
    private Integer totalMult;

    @Column(name = "final_win", nullable = false)
    private Integer finalWin;

    @Column(name = "expanded_count", nullable = false)
    private Integer expandedCount;

    @Column(name = "bonus_triggered", nullable = false)
    private Boolean bonusTriggered;

    @Column(name = "was_free_spin", nullable = false)
    private Boolean wasFreeSpin;

    @Builder.Default
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
