package com.mannschaft.app.match.entity;

import com.mannschaft.app.common.entity.UuidV7Entity;
import com.mannschaft.app.match.domain.HomeAway;
import com.mannschaft.app.match.domain.MatchKind;
import com.mannschaft.app.match.domain.MatchStatus;
import com.mannschaft.app.match.domain.Sport;
import com.mannschaft.app.match.domain.StateModel;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.SQLRestriction;

import java.time.LocalDateTime;

/**
 * F08.10 試合本体（全種別の単一の真実・01 §B.1）。
 *
 * <p>UUIDv7（{@link UuidV7Entity} 継承）・テナントスコープ（organization_id 保持・原則7）・
 * 論理削除あり（deleted_at・原則3）。クロスドメイン参照は ID のみ保持し FK 制約は張らない（原則1）。
 * スコアは matches が正本（home/away_score・PK 戦は home/away_penalty_score で本戦と分離）。</p>
 *
 * <p>設計: docs/features/F08.10_match_record_analytics/01_domain_and_ddl.md §B.1</p>
 */
@Entity
@Table(name = "matches")
@SQLRestriction("deleted_at IS NULL")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
@EqualsAndHashCode(callSuper = true)
public class MatchEntity extends UuidV7Entity {

    /** テナント（organization ドメインへの ID 参照・FK なし・原則1/7） */
    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    /** 記録/ホーム主体チーム（team ドメイン ID 参照・FK なし） */
    @Column(name = "team_id", nullable = false)
    private Long teamId;

    @Enumerated(EnumType.STRING)
    @Column(name = "sport", nullable = false, length = 32)
    private Sport sport;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 16)
    private MatchKind kind;

    /** 大会 fixture リンク（tournament ドメインへの BIGINT ID 参照・NULL=単独試合・FK なし・原則6 据え置き） */
    @Column(name = "tournament_fixture_id")
    private Long tournamentFixtureId;

    /** カレンダー連携（F03.1・schedules への BIGINT ID 参照・FK なし） */
    @Column(name = "schedule_id")
    private Long scheduleId;

    @Enumerated(EnumType.STRING)
    @Column(name = "home_away", nullable = false, length = 16)
    private HomeAway homeAway;

    /** 登録相手チーム（team ドメイン ID 参照・NULL 可・FK なし） */
    @Column(name = "opponent_team_id")
    private Long opponentTeamId;

    @Column(name = "opponent_name", length = 128)
    private String opponentName;

    @Column(name = "kickoff_at")
    private LocalDateTime kickoffAt;

    @Column(name = "venue", length = 200)
    private String venue;

    @Column(name = "duration_minutes")
    private Integer durationMinutes;

    @Column(name = "period_format", length = 32)
    private String periodFormat;

    /** ホーム本戦スコア（正本・延長得点も合算） */
    @Column(name = "home_score")
    private Integer homeScore;

    /** アウェイ本戦スコア（正本・延長得点も合算） */
    @Column(name = "away_score")
    private Integer awayScore;

    /** ホーム PK 戦スコア（本戦と分離） */
    @Column(name = "home_penalty_score")
    private Integer homePenaltyScore;

    /** アウェイ PK 戦スコア（本戦と分離） */
    @Column(name = "away_penalty_score")
    private Integer awayPenaltyScore;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private MatchStatus status;

    /**
     * 状態モデル類型（01 §D.6・CONTINUOUS_TIME/SET_BASED/TURN_BASED）。
     *
     * <p>{@link Sport} から導出可能（{@link Sport#stateModel()}）だが、Service/FE の分岐
     * （タイマー・出場時間算出スキップ・COMPLETED バリデーション）を冪等かつ高速に行うため列として保持する。
     * INSERT 時に sport から導出してセットする（{@link #onCreate()}）。</p>
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "state_model", nullable = false, length = 16)
    private StateModel stateModel;

    /** 記録係ユーザー（公式戦・user ドメイン ID 参照・FK なし） */
    @Column(name = "scorekeeper_user_id")
    private Long scorekeeperUserId;

    /** 記録モード判定（TRUE=公式戦/FALSE=共同記録） */
    @Column(name = "has_scorekeeper", nullable = false)
    private boolean hasScorekeeper;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    /** 作成者（user ドメイン ID 参照・FK なし） */
    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    /** 楽観ロック（メタ更新専用。イベント/appearances 再計算では触れない・02 §E.2） */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (this.createdAt == null) {
            this.createdAt = now;
        }
        this.updatedAt = now;
        if (this.sport == null) {
            this.sport = Sport.SOCCER;
        }
        if (this.homeAway == null) {
            this.homeAway = HomeAway.HOME;
        }
        if (this.status == null) {
            this.status = MatchStatus.SCHEDULED;
        }
        // state_model は sport から導出してセットする（明示指定があればそれを尊重・01 §D.6）。
        if (this.stateModel == null) {
            this.stateModel = this.sport.stateModel();
        }
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
    }
}
