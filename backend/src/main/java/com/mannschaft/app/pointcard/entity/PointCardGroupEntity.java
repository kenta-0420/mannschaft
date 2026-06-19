package com.mannschaft.app.pointcard.entity;

import com.mannschaft.app.common.entity.UuidV7CharEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.experimental.SuperBuilder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * ユーザーが作成するポイントカードグループ。
 *
 * <p>設計書: {@code docs/features/F18_point_card_wallet.md} §5.3
 *
 * <p>例: 「東急ハンズ用」「家族で使う」など、シーン別にカードをまとめる箱。
 * グループ名・絵文字は装飾用シーン名であり PII ではないため暗号化対象外。
 *
 * <p>原則準拠:
 * <ul>
 *   <li>CLAUDE.md 原則 6 — UUIDv7 PK ({@link UuidV7Entity} 継承)</li>
 *   <li>個人スコープのため {@code AbstractUserOwnedRepository} を採用</li>
 *   <li>{@code user_id} は同一個人スコープなので CASCADE 削除 (DDL 側で定義)</li>
 * </ul>
 */
@Entity
@Table(name = "point_card_groups")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
@EqualsAndHashCode(callSuper = true)
public class PointCardGroupEntity extends UuidV7CharEntity {

    /** グループ所有者（users.id）。FK あり ON DELETE CASCADE。 */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** グループ名（最大 64 文字、平文）。 */
    @Column(name = "name", nullable = false, length = 64)
    private String name;

    /** 絵文字 1 文字（UTF-8 で最大 8 バイト、平文）。null 許容。 */
    @Column(name = "emoji", length = 8)
    private String emoji;

    /** グループ一覧の表示順（昇順）。 */
    @Column(name = "display_order", nullable = false)
    @Builder.Default
    private int displayOrder = 0;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        OffsetDateTime now = OffsetDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = OffsetDateTime.now();
    }
}
