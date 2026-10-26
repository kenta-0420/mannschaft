package com.mannschaft.app.schedule.entity;

import com.mannschaft.app.common.entity.UuidV7Entity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.time.Instant;

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
@Table(name = "user_calendar_layer_settings",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_user_calendar_layer",
                columnNames = {"user_id", "scope_type", "scope_id"}))
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

    /**
     * {@code LocalDateTime} でなく {@code Instant} を使う理由（{@code docs/architecture/datetime_policy_utc_instant_vs_wallclock.md}）:
     * {@code created_at}/{@code updated_at} は「行為が発生した1点」を表す瞬間値であり、
     * 前例 {@code ScheduleCommentEntity} に倣い {@code @PrePersist}/{@code @PreUpdate} ＋
     * {@code Instant.now()} で扱う（番人 {@code DateTimeAndZoneGuardTest} は
     * 引数なし {@code LocalDateTime.now()} と {@code LocalDateTime} フィールドを禁止する）。
     */
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
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
        this.updatedAt = Instant.now();
    }
}
