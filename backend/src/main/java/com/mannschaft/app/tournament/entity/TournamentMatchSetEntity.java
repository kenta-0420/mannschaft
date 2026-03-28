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

/**
 * セット別スコアエンティティ。
 */
@Entity
@Table(name = "tournament_match_sets")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class TournamentMatchSetEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long matchId;

    @Column(nullable = false, columnDefinition = "TINYINT UNSIGNED")
    private Integer setNumber;

    @Column(nullable = false)
    private Integer homeScore;

    @Column(nullable = false)
    private Integer awayScore;
}
