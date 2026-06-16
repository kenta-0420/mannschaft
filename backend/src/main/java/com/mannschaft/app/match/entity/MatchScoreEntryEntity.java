package com.mannschaft.app.match.entity;

import com.mannschaft.app.common.entity.UuidV7Entity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * F08.10 採点競技（フィギュアスケート/体操）の<b>多人数順位制の出場者エントリ子表</b>
 * （match ドメイン内・sports/07_scored.md §5B / 01 §B.1.2 / §D.8）。
 *
 * <p>採点競技の MVP は 2 者対戦（{@code matches.home_score}/{@code away_score}）だが、本来形は
 * 「多人数が同一種目に出場し合計点で順位を競う大会」（フィギュア大会・体操の個人総合順位）。
 * 1 match＝1 種目（イベント）に複数の出場者（本エントリ）が並び、合計点降順で順位を導出する
 * （home/away の 2 者モデルを超える後段 Phase の新経路・§5B）。</p>
 *
 * <p>UUIDv7（{@link UuidV7Entity} 継承・原則6）。<b>organization_id / deleted_at は持たない</b>
 * （テナント分離は親 matches・二段アクセス・01 §A.4・IDOR 根絶）。子の削除は親 matches の CASCADE に従う。</p>
 *
 * <p><b>二層正本（再導出パターン・§5B.2）</b>: 出場者エントリ（{@code total_scaled}）と順位
 * （{@code rank_position}）が正本。整合策として {@code matches.home_score}（整数スケール×1000）に
 * 「優勝エントリ or 自チーム最上位エントリの合計点」を補助的に再導出反映し、順位表/ダッシュボードの
 * 既存導線が空にならないようにする（{@code match_sets}・団体戦と同じ二層正本構造）。</p>
 *
 * <p><b>クロスドメイン ID 参照（原則1）</b>: {@code competitor_user_id}（user ドメイン）・
 * {@code competitor_team_id}（team ドメイン）は ID のみ保持し FK を張らない・ORM 関連も張らない。</p>
 *
 * <p>設計: docs/features/F08.10_match_record_analytics/sports/07_scored.md §5B / 01 §B.1.2 / §D.8</p>
 */
@Entity
@Table(name = "match_score_entries")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
@EqualsAndHashCode(callSuper = true)
public class MatchScoreEntryEntity extends UuidV7Entity {

    /** matches(id)（同一ドメイン・DB 上 FK CASCADE）。ID のみ保持し ORM 関連は張らない。 */
    @Column(name = "match_id", nullable = false, columnDefinition = "BINARY(16)")
    private UUID matchId;

    /** 出場選手（user ドメイン ID 参照・未登録は NULL・原則1＝FK なし）。 */
    @Column(name = "competitor_user_id")
    private Long competitorUserId;

    /** 未登録選手名（{@code competitor_user_id} NULL のときの表示名・NULL 許容）。 */
    @Column(name = "competitor_name", length = 128)
    private String competitorName;

    /** 所属チーム（team ドメイン ID 参照・団体採点時・NULL 許容・原則1＝FK なし）。 */
    @Column(name = "competitor_team_id")
    private Long competitorTeamId;

    /** 合計点（整数スケール×1000・§4.1・内訳の集計 or 直接入力）。 */
    @Column(name = "total_scaled", nullable = false)
    private Integer totalScaled;

    /**
     * 順位（合計点の降順で Service が導出・同点は同順位〔次順位を飛ばす標準ルール 1,2,2,4〕・§5B.2 / §6）。
     * 記録時に Service が再計算する（クライアントは設定しない）。
     */
    @Column(name = "rank_position")
    private Integer rankPosition;

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
        if (this.totalScaled == null) {
            this.totalScaled = 0;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
