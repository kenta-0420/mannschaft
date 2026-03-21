package com.mannschaft.app.tournament.entity;

import com.mannschaft.app.tournament.PromotionZone;
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

import java.time.LocalDateTime;

/**
 * 順位表の非正規化キャッシュエンティティ。
 */
@Entity
@Table(name = "tournament_standings")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class TournamentStandingEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long divisionId;

    @Column(nullable = false)
    private Long participantId;

    @Column(name = "`rank`", nullable = false)
    private Integer rank;

    @Column(nullable = false)
    @Builder.Default
    private Integer played = 0;

    @Column(nullable = false)
    @Builder.Default
    private Integer wins = 0;

    @Column(nullable = false)
    @Builder.Default
    private Integer draws = 0;

    @Column(nullable = false)
    @Builder.Default
    private Integer losses = 0;

    @Column(nullable = false)
    @Builder.Default
    private Integer scoreFor = 0;

    @Column(nullable = false)
    @Builder.Default
    private Integer scoreAgainst = 0;

    @Column(nullable = false)
    @Builder.Default
    private Integer scoreDifference = 0;

    @Column(nullable = false)
    @Builder.Default
    private Integer points = 0;

    @Column(nullable = false)
    @Builder.Default
    private Integer bonusPoints = 0;

    @Column(nullable = false)
    @Builder.Default
    private Integer setsWon = 0;

    @Column(nullable = false)
    @Builder.Default
    private Integer setsLost = 0;

    @Column(length = 10)
    private String form;

    @Enumerated(EnumType.STRING)
    private PromotionZone promotionZone;

    @Column(nullable = false)
    @Builder.Default
    private LocalDateTime lastCalculatedAt = LocalDateTime.now();

    /**
     * 順位表データを更新する。
     */
    public void updateStats(Integer rank, Integer played, Integer wins, Integer draws,
                            Integer losses, Integer scoreFor, Integer scoreAgainst,
                            Integer scoreDifference, Integer points, Integer bonusPoints,
                            Integer setsWon, Integer setsLost, String form,
                            PromotionZone promotionZone) {
        this.rank = rank;
        this.played = played;
        this.wins = wins;
        this.draws = draws;
        this.losses = losses;
        this.scoreFor = scoreFor;
        this.scoreAgainst = scoreAgainst;
        this.scoreDifference = scoreDifference;
        this.points = points;
        this.bonusPoints = bonusPoints;
        this.setsWon = setsWon;
        this.setsLost = setsLost;
        this.form = form;
        this.promotionZone = promotionZone;
        this.lastCalculatedAt = LocalDateTime.now();
    }
}
