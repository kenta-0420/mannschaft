package com.mannschaft.app.tournament.fee;

import com.mannschaft.app.common.entity.UuidV7Entity;
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
import lombok.experimental.SuperBuilder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.SQLRestriction;

import java.time.LocalDateTime;

/**
 * 大会参加費エンティティ（F08.7.1/07 §2）。
 *
 * <p>F08.2 の {@code payment_items}（金額・通貨・Stripe Product/Price・grace_period を一元管理）と、
 * tournament ドメインの大会／ディビジョンを結ぶ<strong>薄い連結テーブル</strong>。
 * 金額やステータス等の決済情報は本テーブルに持たず、すべて F08.2 側で管理する（二重管理の回避）。</p>
 *
 * <p>原則準拠:</p>
 * <ul>
 *   <li>新規テーブルゆえ主キーは UUIDv7（原則 6・{@link UuidV7Entity} 継承）。</li>
 *   <li>{@code paymentItemId} / {@code tournamentId} / {@code divisionId} は他ドメインへの ID 参照のみ。
 *       クロスドメイン FK は張らない（原則 1）。</li>
 *   <li>論理削除（soft delete）で履歴を保持し、クロスドメイン CASCADE は使わない（原則 2・3）。</li>
 * </ul>
 */
@Entity
@Table(name = "tournament_fee")
@SQLRestriction("deleted_at IS NULL")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@SuperBuilder(toBuilder = true)
public class TournamentFeeEntity extends UuidV7Entity {

    /** 対象大会（tournaments.id への ID 参照・FK なし／原則1） */
    @Column(nullable = false)
    private Long tournamentId;

    /** 対象ディビジョン（tournament_divisions.id への ID 参照。NULL = 大会全体） */
    private Long divisionId;

    /** payment ドメインの payment_items.id への ID 参照（FK なし／原則1） */
    @Column(nullable = false)
    private Long paymentItemId;

    /** 表示名（例「2026 春季リーグ 参加費」） */
    @Column(nullable = false, length = 255)
    private String title;

    /** 対象範囲（全チーム / 特定チーム） */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private TournamentFeeTargetScope targetScope = TournamentFeeTargetScope.ALL_TEAMS;

    /** 支払期限（NULL = 期限なし）。grace_period は F08.2 の payment_item 側を使用 */
    private LocalDateTime paymentDue;

    /** 主催組織（入金先・テナント絞り込み） */
    @Column(nullable = false)
    private Long organizationId;

    /** 作成した主催組織 ADMIN の user_id（退会時も履歴として保持／設計書 §6） */
    @Column(nullable = false)
    private Long createdBy;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    private LocalDateTime deletedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * 参加費の表示情報を更新する。payment_item_id（金額の出所）は変更不可。
     */
    public void update(String title, Long divisionId, TournamentFeeTargetScope targetScope,
                       LocalDateTime paymentDue) {
        if (title != null) this.title = title;
        this.divisionId = divisionId;
        if (targetScope != null) this.targetScope = targetScope;
        this.paymentDue = paymentDue;
    }

    /**
     * 論理削除を行う。
     */
    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
    }
}
