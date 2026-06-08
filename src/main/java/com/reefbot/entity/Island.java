package com.reefbot.entity;

import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Setter;

import jakarta.persistence.Id;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.OneToOne;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.GenerationType;

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

    @OneToOne
    @JoinColumn(name = "player_id")
    private Player player;

    // ── Resources ─────────────────────────────────────────────────────────────

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

}
