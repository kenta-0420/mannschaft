package com.mannschaft.app.pointcard.entity;

import com.mannschaft.app.common.entity.UuidV7CharEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Builder;
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
 * グループ ↔ カードの中間テーブル。
 *
 * <p>設計書: {@code docs/features/F18_point_card_wallet.md} §5.4
 *
 * <p>同一カードが同一グループ内に重複しないよう UNIQUE 制約 (group_id, card_id) を持つ。
 * 両親（{@code point_card_groups} / {@code user_point_cards}）から ON DELETE CASCADE で連鎖削除される。
 *
 * <p>原則準拠:
 * <ul>
 *   <li>CLAUDE.md 原則 6 — UUIDv7 PK ({@link UuidV7Entity} 継承)</li>
 *   <li>同ドメイン内 CASCADE 許可（DDL 側で定義）</li>
 * </ul>
 */
@Entity
@Table(name = "point_card_group_items",
        uniqueConstraints = @UniqueConstraint(name = "uq_pcgi_group_card",
                columnNames = {"group_id", "card_id"}))
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
@EqualsAndHashCode(callSuper = true)
public class PointCardGroupItemEntity extends UuidV7CharEntity {

    /** 所属グループ ID。FK ON DELETE CASCADE。 */
    @Column(name = "group_id", nullable = false, columnDefinition = "CHAR(36)")
    @JdbcTypeCode(SqlTypes.CHAR)
    private UUID groupId;

    /** 紐付くカード ID。FK ON DELETE CASCADE。 */
    @Column(name = "card_id", nullable = false, columnDefinition = "CHAR(36)")
    @JdbcTypeCode(SqlTypes.CHAR)
    private UUID cardId;

    /** グループ内の提示順序（昇順）。 */
    @Column(name = "display_order", nullable = false)
    @Builder.Default
    private int displayOrder = 0;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = OffsetDateTime.now();
    }
}
