package com.reefbot.entity;

import com.reefbot.enums.ForestActivity;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "player_forest")
public class PlayerForest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne
    @JoinColumn(name = "player_id", nullable = false)
    private Player player;

    @Builder.Default
    private Integer level = 1;

    @Builder.Default
    private Integer xp = 0;

    /** Active activity; null when idle. */
    @Enumerated(EnumType.STRING)
    private ForestActivity activity;

    /** When the active activity finishes; null when idle. */
    private LocalDateTime finishAt;

    /** True once the "activity done" notification has been sent. */
    @Builder.Default
    private Boolean notified = false;
}
