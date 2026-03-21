package com.mannschaft.app.tournament.entity;

import com.mannschaft.app.tournament.PromotionType;
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
 * 昇格・降格の確定記録エンティティ。
 */
@Entity
@Table(name = "tournament_promotion_records")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class TournamentPromotionRecordEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long tournamentId;

    @Column(nullable = false)
    private Long teamId;

    @Column(nullable = false)
    private Long fromDivisionId;

    @Column(nullable = false)
    private Long toDivisionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PromotionType type;

    @Column(nullable = false)
    private Integer finalRank;

    @Column(length = 200)
    private String reason;

    @Column(nullable = false)
    private Long executedBy;

    @Column(nullable = false)
    @Builder.Default
    private LocalDateTime executedAt = LocalDateTime.now();
}
