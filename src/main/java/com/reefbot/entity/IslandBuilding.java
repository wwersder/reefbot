package com.reefbot.entity;

import com.reefbot.enums.BuildingType;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Здание на острове.
 *
 * <p>level = 0 — запись создана, здание ещё не построено (не должно существовать в БД;
 * создаётся с level=1 при первой постройке).
 * <p>buildFinishAt != null — здание/апгрейд в процессе строительства.
 * <p>productionCollectedAt — момент последнего сбора пассивного производства.
 *    NULL означает что сбора не было — считаем производство с момента постройки.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "island_buildings",
       uniqueConstraints = @UniqueConstraint(columnNames = {"island_id", "building_type"}))
public class IslandBuilding {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "island_id", nullable = false)
    private Island island;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private BuildingType buildingType;

    /** Текущий построенный уровень (1+). */
    @Builder.Default
    private Integer level = 1;

    /**
     * Когда завершится текущее строительство/апгрейд.
     * NULL = не строится.
     */
    private LocalDateTime buildFinishAt;

    /**
     * Когда последний раз собирали пассивное производство.
     * NULL = никогда не собирали; производство считается с момента постройки
     * (упрощение: считаем с момента первого сбора или с now()-CAP_HOURS).
     */
    private LocalDateTime productionCollectedAt;
}
