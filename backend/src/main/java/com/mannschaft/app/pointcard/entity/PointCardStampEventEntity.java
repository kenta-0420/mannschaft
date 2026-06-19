package com.mannschaft.app.pointcard.entity;

import com.mannschaft.app.common.entity.UuidV7CharEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * ポイントカード スタンプ押印履歴（証拠ログ）エンティティ。
 *
 * <p>設計書: {@code docs/features/F18_point_card_wallet.md} §12.2 / §5 拡張テーブル
 *
 * <p>Phase 2 で自店スタンプカードの押印操作を記録する証拠ログ。
 * カード ({@code user_point_cards}) と プロバイダー ({@code point_card_providers}) は同一機能内のため FK を許容し、
 * {@code organization_id} と {@code pressed_by_user_id} はクロスドメイン参照のため INDEX のみで運用する
 * （CLAUDE.md 原則 1 / 原則 2）。
 *
 * <p>CLAUDE.md 原則 6 に従い PK は UUIDv7（CHAR(36)）。
 * 「organization_id + 押印者 user_id」のスコープであり個人スコープではないため、
 * {@code AbstractUserOwnedRepository} は使わず {@code JpaRepository} を直接利用する。
 */
@Entity
@Table(name = "point_card_stamp_events")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@SuperBuilder(toBuilder = true)
@EqualsAndHashCode(callSuper = true)
public class PointCardStampEventEntity extends UuidV7CharEntity {

    /** 対象カード ID（user_point_cards.id 参照）。 */
    @Column(name = "card_id", nullable = false, columnDefinition = "CHAR(36)")
    @JdbcTypeCode(SqlTypes.CHAR)
    private UUID cardId;

    /** プロバイダー ID（point_card_providers.id 参照、自店発行プロバイダー）。 */
    @Column(name = "provider_id", nullable = false, columnDefinition = "CHAR(36)")
    @JdbcTypeCode(SqlTypes.CHAR)
    private UUID providerId;

    /** プロバイダーを発行した組織 ID（クロスドメイン弱参照）。 */
    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    /** スタンプ増減数（正: 押印 / 負: 取り消し）。0 は CHECK 制約で拒否。 */
    @Column(name = "delta", nullable = false)
    private Integer delta;

    /** 押印を実施した店員ユーザー ID（クロスドメイン弱参照）。 */
    @Column(name = "pressed_by_user_id", nullable = false)
    private Long pressedByUserId;

    /** 押印実施時刻（クライアント表示用）。 */
    @Column(name = "pressed_at", nullable = false)
    private OffsetDateTime pressedAt;

    /** 任意メモ（運営側コメント、例: 「特典付与」「誤押印取消」）。 */
    @Column(name = "memo", length = 200)
    private String memo;

    /** レコード作成時刻（DB 監査用）。 */
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        OffsetDateTime now = OffsetDateTime.now();
        if (this.pressedAt == null) {
            this.pressedAt = now;
        }
        this.createdAt = now;
    }
}
