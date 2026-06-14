package com.mannschaft.app.match.entity;

import com.mannschaft.app.common.entity.UuidV7Entity;
import com.mannschaft.app.match.domain.TeamSide;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * F08.10 セット制スコア子表（バレーボール・match ドメイン内・01 §B.5）。
 *
 * <p>UUIDv7（{@link UuidV7Entity} 継承・原則6）。<b>organization_id / deleted_at は持たない</b>
 * （テナント分離は親 matches・二段アクセス・01 §A.4・IDOR 根絶）。子の削除は親 matches の CASCADE に従う。</p>
 *
 * <p>セット内スコアの正本は本表（{@code home_points}/{@code away_points}/{@code winner_side}）であり、
 * 獲得セット数（試合の本戦スコア）は {@code matches.home_score/away_score} に集計反映する（§B.1.2）。</p>
 *
 * <p>設計: docs/features/F08.10_match_record_analytics/01_domain_and_ddl.md §B.5
 *   / sports/04_volleyball.md §3 / §4</p>
 */
@Entity
@Table(name = "match_sets")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
@EqualsAndHashCode(callSuper = true)
public class MatchSetEntity extends UuidV7Entity {

    /** matches(id)（同一ドメイン・DB 上 FK CASCADE）。ID のみ保持し ORM 関連は張らない。 */
    @Column(name = "match_id", nullable = false, columnDefinition = "BINARY(16)")
    private UUID matchId;

    /** セット番号（1〜5・best-of-5）。 */
    @Column(name = "set_number", nullable = false)
    private Integer setNumber;

    /** 当該セットのホーム得点（ラリーポイント・§4.1）。 */
    @Column(name = "home_points", nullable = false)
    private Integer homePoints;

    /** 当該セットのアウェイ得点。 */
    @Column(name = "away_points", nullable = false)
    private Integer awayPoints;

    /** セット勝者（SET_END でデュース条件達成時に確定・未決着は NULL・§4.2）。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "winner_side", length = 16)
    private TeamSide winnerSide;

    /** 最終第 5 セット（15 点制・デュース）フラグ。 */
    @Column(name = "is_final_set", nullable = false)
    private boolean finalSet;

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
        if (this.homePoints == null) {
            this.homePoints = 0;
        }
        if (this.awayPoints == null) {
            this.awayPoints = 0;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
