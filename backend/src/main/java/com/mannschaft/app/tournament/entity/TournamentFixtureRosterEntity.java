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

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 試合出場メンバー登録エンティティ。
 */
@Entity
@Table(name = "tournament_match_rosters")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class TournamentFixtureRosterEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long matchId;

    @Column(nullable = false)
    private Long participantId;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false)
    @Builder.Default
    private Boolean isStarter = true;

    private Integer jerseyNumber;

    @Column(length = 30)
    private String position;

    /** 協会選手登録番号（背番号 jerseyNumber とは別・NULL 可／F08.7.1/05 §8.1） */
    @Column(length = 32)
    private String registrationNumber;

    /** 着用 team_uniform_set への ID 参照（team ドメイン・クロスドメイン FK なし／原則1・NULL 可／§8.2） */
    @Column(columnDefinition = "BINARY(16)")
    private UUID uniformSetId;

    @Column(nullable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();
}
