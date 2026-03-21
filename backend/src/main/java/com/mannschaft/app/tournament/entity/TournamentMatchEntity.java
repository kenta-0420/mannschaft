package com.mannschaft.app.tournament.entity;

import com.mannschaft.app.common.BaseEntity;
import com.mannschaft.app.tournament.MatchResult;
import com.mannschaft.app.tournament.MatchSlot;
import com.mannschaft.app.tournament.MatchStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 対戦カードエンティティ。
 */
@Entity
@Table(name = "tournament_matches")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class TournamentMatchEntity extends BaseEntity {

    @Column(nullable = false)
    private Long matchdayId;

    private Long homeParticipantId;

    private Long awayParticipantId;

    private Integer matchNumber;

    private LocalDateTime scheduledDatetime;

    @Column(length = 200)
    private String venue;

    private Integer homeScore;

    private Integer awayScore;

    private Integer homeExtraScore;

    private Integer awayExtraScore;

    private Integer homePenaltyScore;

    private Integer awayPenaltyScore;

    private Long winnerParticipantId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private MatchResult result = MatchResult.PENDING;

    @Column(nullable = false)
    @Builder.Default
    private Integer leg = 1;

    private Long nextMatchId;

    @Enumerated(EnumType.STRING)
    private MatchSlot nextMatchSlot;

    @Column(columnDefinition = "TEXT")
    private String notes;

    private Long scheduleId;

    @Version
    @Column(nullable = false)
    @Builder.Default
    private Long version = 0L;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private MatchStatus status = MatchStatus.SCHEDULED;

    /**
     * スコアを入力・更新する。
     */
    public void updateScore(Integer homeScore, Integer awayScore,
                            Integer homeExtraScore, Integer awayExtraScore,
                            Integer homePenaltyScore, Integer awayPenaltyScore,
                            Long winnerParticipantId, MatchResult result, String notes) {
        this.homeScore = homeScore;
        this.awayScore = awayScore;
        this.homeExtraScore = homeExtraScore;
        this.awayExtraScore = awayExtraScore;
        this.homePenaltyScore = homePenaltyScore;
        this.awayPenaltyScore = awayPenaltyScore;
        this.winnerParticipantId = winnerParticipantId;
        this.result = result;
        this.notes = notes;
        this.status = MatchStatus.COMPLETED;
    }

    /**
     * ステータスを変更する。
     */
    public void changeStatus(MatchStatus newStatus) {
        this.status = newStatus;
    }

    /**
     * 勝者が次の試合に進む際にスロットを設定する。
     */
    public void setNextMatch(Long nextMatchId, MatchSlot slot) {
        this.nextMatchId = nextMatchId;
        this.nextMatchSlot = slot;
    }
}
