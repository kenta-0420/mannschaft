package com.mannschaft.app.pointcard.entity;

import com.mannschaft.app.common.entity.UuidV7CharEntity;
import com.mannschaft.app.pointcard.enums.BalanceOperationType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.experimental.SuperBuilder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * ポイントカード 残高変動履歴エンティティ（残高型）。
 *
 * <p>設計書: {@code docs/features/F18_point_card_wallet.md} §12.1 / §16
 *
 * <p>Phase 3 で自店発行残高型カード（{@code SELF_ISSUED_BALANCE}）の入金・利用・返金を記録する
 * 取引イベントログ。カード ({@code user_point_cards}) と プロバイダー ({@code point_card_providers}) は
 * 同一機能内のため FK を許容し、{@code organization_id} と {@code operated_by_user_id} は
 * クロスドメイン参照のため INDEX のみで運用する（CLAUDE.md 原則 1 / 原則 2）。
 *
 * <p>CLAUDE.md 原則 6 に従い PK は UUIDv7（CHAR(36)）。
 */
@Entity
@Table(name = "point_card_balance_events")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@SuperBuilder(toBuilder = true)
@EqualsAndHashCode(callSuper = true)
public class PointCardBalanceEventEntity extends UuidV7CharEntity {

    /** 対象カード ID（user_point_cards.id 参照）。 */
    @Column(name = "card_id", nullable = false, columnDefinition = "CHAR(36)")
    @JdbcTypeCode(SqlTypes.CHAR)
    private UUID cardId;

    /** プロバイダー ID（point_card_providers.id 参照、SELF_ISSUED_BALANCE 種別）。 */
    @Column(name = "provider_id", nullable = false, columnDefinition = "CHAR(36)")
    @JdbcTypeCode(SqlTypes.CHAR)
    private UUID providerId;

    /** プロバイダーを発行した組織 ID（クロスドメイン弱参照）。 */
    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    /** 操作種別（CHARGE / SPENT / REFUND）。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "operation_type", nullable = false, length = 20)
    private BalanceOperationType operationType;

    /**
     * 残高増減額。CHARGE/REFUND は正、SPENT は負。0 は CHECK 制約で拒否。
     * ±1,000,000 円までを許容（DB CHECK + Service 二段ガード）。
     */
    @Column(name = "delta", nullable = false, precision = 12, scale = 2)
    private BigDecimal delta;

    /**
     * 反映後の残高。0 〜 10,000,000 円。
     * DB CHECK で下限・上限を保証し、Service 層でも事前に弾く。
     */
    @Column(name = "balance_after", nullable = false, precision = 12, scale = 2)
    private BigDecimal balanceAfter;

    /**
     * 返金時、元の操作 event ID を参照。
     * REFUND 以外では NULL。元 event 削除時は ON DELETE SET NULL（自己参照 FK）。
     */
    @Column(name = "refund_of_event_id", columnDefinition = "CHAR(36)")
    @JdbcTypeCode(SqlTypes.CHAR)
    private UUID refundOfEventId;

    /** 操作を実施した店員ユーザー ID（クロスドメイン弱参照）。 */
    @Column(name = "operated_by_user_id", nullable = false)
    private Long operatedByUserId;

    /** 操作実施時刻（クライアント表示用）。 */
    @Column(name = "operated_at", nullable = false)
    private OffsetDateTime operatedAt;

    /** 任意メモ（運営側コメント、例: 「キャンペーン入金」「誤チャージ取消」）。 */
    @Column(name = "note", length = 200)
    private String note;

    /** レコード作成時刻（DB 監査用）。 */
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        OffsetDateTime now = OffsetDateTime.now();
        if (this.operatedAt == null) {
            this.operatedAt = now;
        }
        this.createdAt = now;
    }
}
