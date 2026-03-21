package com.mannschaft.app.tournament.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * 個人ランキングの非正規化キャッシュエンティティ。
 */
@Entity
@Table(name = "tournament_individual_rankings")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class TournamentIndividualRankingEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long tournamentId;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false)
    private Long participantId;

    @Column(nullable = false, length = 30)
    private String statKey;

    @Column(name = "`rank`", nullable = false)
    private Integer rank;

    private Integer totalValueInt;

    @Column(precision = 15, scale = 4)
    private BigDecimal totalValueDecimal;

    private LocalTime totalValueTime;

    @Column(nullable = false)
    @Builder.Default
    private Integer matchesPlayed = 0;

    @Column(nullable = false)
    @Builder.Default
    private LocalDateTime lastCalculatedAt = LocalDateTime.now();

    /**
     * ランキングデータを更新する。
     */
    public void updateRanking(Integer rank, Integer totalValueInt,
                              BigDecimal totalValueDecimal, LocalTime totalValueTime,
                              Integer matchesPlayed) {
        this.rank = rank;
        this.totalValueInt = totalValueInt;
        this.totalValueDecimal = totalValueDecimal;
        this.totalValueTime = totalValueTime;
        this.matchesPlayed = matchesPlayed;
        this.lastCalculatedAt = LocalDateTime.now();
    }
}
