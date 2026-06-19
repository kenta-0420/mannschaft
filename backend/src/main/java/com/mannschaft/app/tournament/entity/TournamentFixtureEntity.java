package com.mannschaft.app.tournament.entity;

import com.mannschaft.app.common.BaseEntity;
import com.mannschaft.app.tournament.FixtureResult;
import com.mannschaft.app.tournament.FixtureSlot;
import com.mannschaft.app.tournament.FixtureStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.experimental.SuperBuilder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 対戦カード（fixture）エンティティ。
 *
 * <p><b>スコアの正本は matches ドメイン（Phase 5b-1 で正式宣言・05 §H.1〜H.2.3）</b>:
 * 本エンティティが持つスコア系列（{@code homeScore} / {@code awayScore} /
 * {@code homePenaltyScore} / {@code awayPenaltyScore} / {@code result} /
 * {@code winnerParticipantId} / {@code status}）の<b>正本は matches ドメイン</b>
 * （{@code matches.home_score} 等）である。本エンティティの当該列は
 * <b>順位表・個人ランキング計算を高速化するための派生スナップショット</b>であり
 * （実体化ビュー・05 §H.2.3）、クロスドメイン JOIN（CLAUDE.md 原則 1 違反・N+1）を
 * 避けるため fixture 自ドメインに保持する。順位/ランキング計算は本スナップショット列を
 * 参照し、matches へ直接 JOIN しない。</p>
 *
 * <p><b>スナップショット列への書込元は次の 2 経路に限る</b>:</p>
 * <ol>
 *   <li><b>入口①（matches 正本からの同期）</b>:
 *       {@link com.mannschaft.app.tournament.listener.MatchScoreFixtureListener} が
 *       {@code MatchCompletedEvent}（AFTER_COMMIT）を受け、
 *       {@link com.mannschaft.app.tournament.service.FixtureService#updateScore} 経由で
 *       matches のスコアをスナップショットへコピーする（05 §H.2.3）。</li>
 *   <li><b>F08.7 直接入力</b>:
 *       {@link com.mannschaft.app.tournament.service.FixtureService#updateScore} /
 *       {@code batchUpdateScores}（スコア入力グリッド・CSV 取込）。
 *       <b>※ 系統 B（直接入力）の matches 正本化は Phase 5b-2' 予定</b>であり、
 *       本フェーズ（5b-1）では振る舞いを変えない。</li>
 * </ol>
 *
 * <p>設計: docs/features/F08.10_match_record_analytics/05_tournament_integration.md §H.1〜H.2.3</p>
 */
@Entity
@Table(name = "tournament_matches")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@SuperBuilder(toBuilder = true)
public class TournamentFixtureEntity extends BaseEntity {

    @Column(nullable = false)
    private Long matchdayId;

    private Long homeParticipantId;

    private Long awayParticipantId;

    private Integer matchNumber;

    private LocalDateTime scheduledDatetime;

    @Column(length = 200)
    private String venue;

    /** 本戦ホーム得点（matches 正本の派生スナップショット・05 §H.2.3）。順位計算はこの列を参照する。 */
    private Integer homeScore;

    /** 本戦アウェイ得点（matches 正本の派生スナップショット・05 §H.2.3）。順位計算はこの列を参照する。 */
    private Integer awayScore;

    // 延長別スコア列（home_extra_score / away_extra_score）は Phase 5b-3（Contract）で廃止した。
    // 延長得点は本戦スコア（homeScore / awayScore）へ合算済みであり、延長別列は不要
    // （05 §H.1 移行表・sports/01_soccer.md §4.1）。勝敗判定・順位は本戦スコアで完結する。

    /** PK 戦ホーム得点（matches 正本の派生スナップショット・05 §H.2.3）。 */
    private Integer homePenaltyScore;

    /** PK 戦アウェイ得点（matches 正本の派生スナップショット・05 §H.2.3）。 */
    private Integer awayPenaltyScore;

    /** 勝者 participant（matches 正本の派生スナップショット・05 §H.2.3）。順位計算が fixture 内で完結するよう保持する。 */
    private Long winnerParticipantId;

    /** 勝敗結果（matches 正本の派生スナップショット・05 §H.2.3）。順位計算が fixture 内で完結するよう保持する。 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private FixtureResult result = FixtureResult.PENDING;

    @Column(nullable = false, columnDefinition = "TINYINT UNSIGNED")
    @Builder.Default
    private Integer leg = 1;

    private Long nextMatchId;

    @Enumerated(EnumType.STRING)
    private FixtureSlot nextMatchSlot;

    @Column(columnDefinition = "TEXT")
    private String notes;

    private Long scheduleId;

    /** メンバー表提出締切（NULL=締切なし／F08.7.1/05 §2）。締切後の自チーム提出は 409 でロック */
    private LocalDateTime rosterDeadline;

    @Version
    @Column(nullable = false)
    @Builder.Default
    private Long version = 0L;

    /**
     * 試合ステータス（matches 正本の派生スナップショット・05 §H.1 移行表・H.2.3）。
     * 順位計算の対象抽出（COMPLETED 抽出）はこの列を参照し、matches を直接見ない。
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private FixtureStatus status = FixtureStatus.SCHEDULED;

    /**
     * スコアを入力・更新する。
     *
     * <p><b>スナップショット列の更新（05 §H.2.3）</b>: 本メソッドは matches 正本由来の派生
     * スナップショット列を更新する。書込元は {@code FixtureService.updateScore} /
     * {@code batchUpdateScores} に限る（クラス Javadoc 参照）。冪等（全列上書き・置換）。</p>
     */
    public void updateScore(Integer homeScore, Integer awayScore,
                            Integer homePenaltyScore, Integer awayPenaltyScore,
                            Long winnerParticipantId, FixtureResult result, String notes) {
        this.homeScore = homeScore;
        this.awayScore = awayScore;
        this.homePenaltyScore = homePenaltyScore;
        this.awayPenaltyScore = awayPenaltyScore;
        this.winnerParticipantId = winnerParticipantId;
        this.result = result;
        this.notes = notes;
        this.status = FixtureStatus.COMPLETED;
    }

    /**
     * ステータスを変更する。
     */
    public void changeStatus(FixtureStatus newStatus) {
        this.status = newStatus;
    }

    /**
     * 勝者が次の試合に進む際にスロットを設定する。
     */
    public void setNextMatch(Long nextMatchId, FixtureSlot slot) {
        this.nextMatchId = nextMatchId;
        this.nextMatchSlot = slot;
    }

    /**
     * メンバー表提出締切を設定する（主催組織 ADMIN・F08.7.1/05 §2）。
     * NULL を渡すと締切なし（ロック解除）になる。
     */
    public void setRosterDeadline(LocalDateTime rosterDeadline) {
        this.rosterDeadline = rosterDeadline;
    }
}
