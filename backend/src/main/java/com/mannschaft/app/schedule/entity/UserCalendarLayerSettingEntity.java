package com.mannschaft.app.schedule.entity;

import com.mannschaft.app.common.entity.UuidV7Entity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;

/**
 * ユーザー×カレンダーレイヤーの表示設定エンティティ（F03.19）。
 *
 * <p>設計書: {@code docs/features/F03.19_unified_calendar_view.md} §3.1。
 * 主キーは {@link UuidV7Entity} を継承した UUIDv7（DDL は {@code BINARY(16)}）。
 * {@code user_id} / {@code scope_id} はいずれもクロスドメイン論理参照であり FK は張らない（原則1）。</p>
 *
 * <p>{@code scope_type = PERSONAL} の行は {@code scope_id = 0}（センチネル）とする。
 * 設定行が無いレイヤーは「自動色」（§3.3）にフォールバックするため、本表は論理削除を持たず、
 * 削除＝物理削除でよい（原則3 の対象外。ユーザーの個人設定でありコアエンティティではない）。</p>
 */
@Entity
@Table(name = "user_calendar_layer_settings")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PUBLIC)
@SuperBuilder(toBuilder = true)
@EqualsAndHashCode(callSuper = true)
public class UserCalendarLayerSettingEntity extends UuidV7Entity {

    /** 設定の所有者（users.id を論理参照。本人以外は読み書き不可）。 */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** レイヤー種別（PERSONAL / TEAM / ORGANIZATION）。 */
    @Column(name = "scope_type", nullable = false, length = 20)
    private String scopeType;

    /** レイヤー対象ID（TEAM=teams.id / ORGANIZATION=organizations.id / PERSONAL=0 センチネル）。 */
    @Column(name = "scope_id", nullable = false)
    private Long scopeId;

    /** ユーザー指定色（#RRGGBB 大文字）。NULL の場合は自動色にフォールバックする。 */
    @Column(name = "color", length = 7)
    private String color;

    /** 既定で非表示にするか（フィルタの初期状態）。 */
    @Column(name = "hidden", nullable = false)
    private Boolean hidden;

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
        if (this.scopeId == null) {
            this.scopeId = 0L;
        }
        if (this.hidden == null) {
            this.hidden = false;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
