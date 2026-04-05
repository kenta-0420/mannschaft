package com.mannschaft.app.tournament.entity;

import com.mannschaft.app.common.BaseEntity;
import com.mannschaft.app.tournament.MatchdayStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * 節・ラウンドエンティティ。
 */
@Entity
@Table(name = "tournament_matchdays")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class TournamentMatchdayEntity extends BaseEntity {

    @Column(nullable = false)
    private Long divisionId;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false)
    private Integer matchdayNumber;

    private LocalDate scheduledDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private MatchdayStatus status = MatchdayStatus.SCHEDULED;

    /**
     * 節情報を更新する。
     */
    public void update(String name, Integer matchdayNumber, LocalDate scheduledDate) {
        this.name = name;
        this.matchdayNumber = matchdayNumber;
        this.scheduledDate = scheduledDate;
    }

    /**
     * ステータスを変更する。
     */
    public void changeStatus(MatchdayStatus newStatus) {
        this.status = newStatus;
    }
}
