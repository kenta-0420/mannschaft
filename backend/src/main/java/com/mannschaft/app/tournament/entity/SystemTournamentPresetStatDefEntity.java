package com.mannschaft.app.tournament.entity;

import com.mannschaft.app.tournament.StatAggregationType;
import com.mannschaft.app.tournament.StatDataType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * プリセットの個人成績項目定義エンティティ。
 */
@Entity
@Table(name = "system_tournament_preset_stat_defs")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class SystemTournamentPresetStatDefEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long presetId;

    @Column(nullable = false, length = 50)
    private String name;

    @Column(nullable = false, length = 30)
    private String statKey;

    @Column(length = 20)
    private String unit;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private StatDataType dataType = StatDataType.INTEGER;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private StatAggregationType aggregationType = StatAggregationType.SUM;

    @Column(nullable = false)
    @Builder.Default
    private Boolean isRankingTarget = true;

    @Column(length = 50)
    private String rankingLabel;

    @Column(nullable = false)
    @Builder.Default
    private Integer sortOrder = 0;
}
