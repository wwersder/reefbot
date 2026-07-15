package com.reefbot.entity;

import jakarta.persistence.*;
import lombok.*;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "islands")
public class Island {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;

    private Integer level;

    @Builder.Default
    private Integer wood = 0;

    @Builder.Default
    private Integer stone = 0;

    @Builder.Default
    private Integer fish = 0;

    @Builder.Default
    private Integer shells = 0;

    @Builder.Default
    private Integer coral = 0;

    @Builder.Default
    private Integer meat = 0;

    @Builder.Default
    private Integer fur = 0;

    @Builder.Default
    private Integer storageCapacity = 100;

    @Builder.Default
    private Integer devPoints = 0;

    @OneToOne
    @JoinColumn(name = "player_id")
    private Player player;
}
