package com.mannschaft.app.match.entity;

import com.mannschaft.app.common.entity.UuidV7Entity;
import com.mannschaft.app.match.domain.ScoredApparatus;
import com.mannschaft.app.match.domain.ScoredComponentType;
import com.mannschaft.app.match.domain.TeamSide;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.experimental.SuperBuilder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * F08.10 採点競技（フィギュアスケート/体操）の<b>審判別/種目別採点内訳子表</b>
 * （match ドメイン内・sports/07_scored.md §4B / 01 §B.1.2 / §D.8）。
 *
 * <p>UUIDv7（{@link UuidV7Entity} 継承・原則6）。<b>organization_id / deleted_at は持たない</b>
 * （テナント分離は親 matches・二段アクセス・01 §A.4・IDOR 根絶）。子の削除は親 matches の CASCADE に従う。</p>
 *
 * <p><b>二層正本（再導出パターン・§4B.2）</b>: 採点内訳の正本は本表（{@code component_type}/{@code points_scaled}）、
 * 合計点（試合の本戦スコア）は {@code matches.home_score/away_score}（整数スケール×1000）に集計反映する。
 * これは {@code match_sets}（セット内得点→獲得セット数）・団体戦（子ボード勝ち星→親列）と全く同じ二層正本構造。</p>
 *
 * <p><b>{@code competitor_side} と {@code score_entry_id} の使い分け</b>: 2 者対戦（MVP・§5）なら
 * {@code competitor_side}（HOME/AWAY）で内訳を束ねる。多人数順位制（§5B・後段 Phase・別タスク）導入時は
 * {@code score_entry_id} で束ねる（両方 NULL 許容で対戦モデルに応じて使い分け・本タスクは 2 者対戦のみ実装）。</p>
 *
 * <p>設計: docs/features/F08.10_match_record_analytics/sports/07_scored.md §4B / 01 §B.1.2 / §D.8</p>
 */
@Entity
@Table(name = "match_scored_components")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
@EqualsAndHashCode(callSuper = true)
public class MatchScoredComponentEntity extends UuidV7Entity {

    /** matches(id)（同一ドメイン・DB 上 FK CASCADE）。ID のみ保持し ORM 関連は張らない。 */
    @Column(name = "match_id", nullable = false, columnDefinition = "BINARY(16)")
    private UUID matchId;

    /** 2 者対戦時の side（MVP・HOME/AWAY）。多人数順位制導入時は NULL（score_entry_id を使う）。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "competitor_side", length = 16)
    private TeamSide competitorSide;

    /**
     * 多人数順位制（§5B・後段 Phase・別タスク）のエントリ参照（2 者対戦時は NULL）。
     * 本タスクでは未使用（列のみ設計済 DDL に存在）。
     */
    @Column(name = "score_entry_id", columnDefinition = "BINARY(16)")
    private UUID scoreEntryId;

    /** 種目/セグメント（体操の FLOOR… フィギュアの SP/FS・NULL 許容＝種目を区別しない内訳）。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "apparatus", length = 32)
    private ScoredApparatus apparatus;

    /** 審判識別（J1〜J9 等・審判別素点を持つフィギュア GOE 用・集計のみなら NULL）。 */
    @Column(name = "judge_label", length = 32)
    private String judgeLabel;

    /** 項目（フィギュア=TES/PCS/DEDUCTION・体操=D_SCORE/E_SCORE・競技別カタログ列挙・NOT NULL）。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "component_type", nullable = false, length = 32)
    private ScoredComponentType componentType;

    /**
     * 当該項目の点数（整数スケール＝×1000・小数は表示で復元・§4.1 と整合）。
     *
     * <p>DEDUCTION（減点）は負方向の項目であり、合計集計時に符号付きで加算される
     * （Service が DEDUCTION を減算扱いする・§4B.2）。</p>
     */
    @Column(name = "points_scaled", nullable = false)
    private Integer pointsScaled;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (this.createdAt == null) {
            this.createdAt = now;
        }
        this.updatedAt = now;
        if (this.pointsScaled == null) {
            this.pointsScaled = 0;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
