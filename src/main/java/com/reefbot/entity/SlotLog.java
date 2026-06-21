package com.reefbot.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "slot_logs")
public class SlotLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "player_id", nullable = false)
    private Player player;

    private int     bet;
    private int     win;
    private int     scatterCount;
    private boolean wasFreeSpin;
    private boolean triggeredBonus;

    @Column(length = 600)
    private String gridJson;

    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();
}
