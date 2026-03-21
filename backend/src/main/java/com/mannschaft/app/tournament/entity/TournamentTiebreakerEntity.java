package com.mannschaft.app.tournament.entity;

import com.mannschaft.app.tournament.TiebreakerCriteria;
import com.mannschaft.app.tournament.TiebreakerDirection;
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
 * 大会のタイブレーク優先順位エンティティ。
 */
@Entity
@Table(name = "tournament_tiebreakers")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class TournamentTiebreakerEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long tournamentId;

    @Column(nullable = false)
    private Integer priority;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TiebreakerCriteria criteria;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private TiebreakerDirection direction = TiebreakerDirection.DESC;
}
