package com.reefbot.entity;

import com.reefbot.enums.BuildingStatus;
import com.reefbot.enums.BuildingType;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "buildings")
public class Building {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "island_id", nullable = false)
    private Island island;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private BuildingType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private BuildingStatus status;

    @Builder.Default
    private Integer level = 1;

    @Column(nullable = false)
    private LocalDateTime startedAt;

    @Column(nullable = false)
    private LocalDateTime finishAt;

    /** Timestamp of the last resource collection from this building. */
    private LocalDateTime lastCollectedAt;

    /** True once a push notification about this build's completion has been sent. */
    @Builder.Default
    private Boolean notificationSent = false;
}
