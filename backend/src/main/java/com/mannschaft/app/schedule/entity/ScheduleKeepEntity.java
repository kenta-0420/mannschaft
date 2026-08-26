package com.mannschaft.app.schedule.entity;

import com.mannschaft.app.common.entity.UuidV7Entity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.SQLRestriction;

import java.time.LocalDateTime;

/**
 * キープ（日付未定の予定）エンティティ（F03.17）。
 *
 * <p>設計書: {@code docs/features/F03.17_schedule_keep.md} §3。
 * 主キーは {@link UuidV7Entity} を継承した UUIDv7（DDL は {@code BINARY(16)}）。
 * {@link UuidV7Entity} は監査カラムを持たないため、{@code createdAt}/{@code updatedAt}/
 * {@code deletedAt} は本クラスで自前宣言する（§3.2.2。{@code VillageMeetupEntity} と同じ構成）。</p>
 *
 * <p>{@code team_id}/{@code organization_id}/{@code user_id} は
 * {@code ck_schedule_keeps_scope_xor}（DB CHECK）により排他が保証される（§2.3）。
 * いずれもクロスドメイン参照のため FK は張らず、ID 参照＋index のみとする（原則1）。</p>
 *
 * <p>{@code converted_schedule_id} は変換先 {@code schedules.id}（BIGINT）への ID 参照。
 * FK は張らない（§3.5）。</p>
 */
@Entity
@Table(name = "schedule_keeps")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PUBLIC)
@SuperBuilder(toBuilder = true)
@EqualsAndHashCode(callSuper = true)
@SQLRestriction("deleted_at IS NULL")
public class ScheduleKeepEntity extends UuidV7Entity {

    /** チームスコープ（teams.id を ID 参照。FK は張らない）。 */
    @Column(name = "team_id")
    private Long teamId;

    /** 組織スコープ（organizations.id を ID 参照。FK は張らない）。 */
    @Column(name = "organization_id")
    private Long organizationId;

    /** 個人スコープ（users.id を ID 参照。FK は張らない）。 */
    @Column(name = "user_id")
    private Long userId;

    /** 唯一の必須入力。 */
    @Column(name = "title", nullable = false, length = 200)
    private String title;

    @Column(name = "memo", columnDefinition = "TEXT")
    private String memo;

    /**
     * 候補日（JSON 配列文字列。ISO-8601 の {@code YYYY-MM-DD}）。
     * Service 層で {@code List<LocalDate>} へ変換する（§3.4）。
     */
    @Column(name = "candidate_dates", columnDefinition = "JSON")
    private String candidateDates;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ScheduleKeepStatus status;

    /** 変換で生成した schedules.id（参照先の型に合わせ BIGINT。FK は張らない）。 */
    @Column(name = "converted_schedule_id")
    private Long convertedScheduleId;

    /** 一覧の手動並び順（小さいほど上）。gap 採番。 */
    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder;

    /** 作成者（users.id を ID 参照）。個人スコープでは user_id と一致。 */
    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /** 論理削除（原則3）。 */
    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (this.createdAt == null) {
            this.createdAt = now;
        }
        this.updatedAt = now;
        if (this.status == null) {
            this.status = ScheduleKeepStatus.KEPT;
        }
        if (this.sortOrder == null) {
            this.sortOrder = 0;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
