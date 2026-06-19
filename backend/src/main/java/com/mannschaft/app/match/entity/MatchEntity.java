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
import lombok.experimental.SuperBuilder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.SQLRestriction;

import java.time.LocalDateTime;
import java.util.UUID;

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
@SuperBuilder(toBuilder = true)
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

    /**
     * 総手数（ターン制のみ・球技では NULL・sports/05_shogi.md §3 / 01 §B.1）。
     *
     * <p>将棋/囲碁の進行量的指標。{@code MatchEventType.MOVE_COUNT} イベント or 試合詳細での直接入力で記録。
     * 任意（NULL 可）。SMALLINT UNSIGNED 相当。</p>
     */
    @Column(name = "total_moves")
    private Integer totalMoves;

    /**
     * 勝ち方（ターン制のみ・競技別カタログ enum 文字列・球技では NULL・01 §B.1 / §D.7）。
     *
     * <p>将棋＝{@link com.mannschaft.app.match.catalog.ShogiWinMethod}・囲碁＝
     * {@link com.mannschaft.app.match.catalog.GoWinMethod} の enum 名を VARCHAR(32) で保持する
     * （勝ち方の正準は本列に統一）。「どう勝ったか」を表し、「どちらが勝ったか」は home/away_score の大小
     * （責務分離・§B.1.2）。引き分け（千日手/持将棋/持碁）は本列 NULL＋両スコア 0。</p>
     */
    @Column(name = "win_method", length = 32)
    private String winMethod;

    /**
     * 団体戦の親 match（個人戦=NULL／団体戦の子ボードのみ設定・自己参照・同一 match ドメイン・01 §B.6）。
     *
     * <p>DB 上は自己参照 FK＋ON DELETE CASCADE（親団体戦の物理削除で子ボードも消える・同一ドメインゆえ可）。
     * ID のみ保持し ORM 関連は張らない（二段アクセス・子直引き禁止・01 §A.4 / §C.4）。</p>
     */
    @Column(name = "parent_match_id", columnDefinition = "BINARY(16)")
    private UUID parentMatchId;

    /**
     * ボード順（団体戦の子のみ・1=大将/主将 等・将棋/囲碁・01 §B.6）。
     *
     * <p>個人戦・団体戦の親は NULL。SMALLINT UNSIGNED 相当。同一親の中で連番。</p>
     */
    @Column(name = "board_number")
    private Integer boardNumber;

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
