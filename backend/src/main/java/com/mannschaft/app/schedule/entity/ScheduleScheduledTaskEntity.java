package com.mannschaft.app.schedule.entity;

import com.mannschaft.app.common.entity.UuidV7Entity;
import com.mannschaft.app.schedule.CalendarSyncScopeType;
import com.mannschaft.app.schedule.ScheduledTaskStatus;
import com.mannschaft.app.schedule.ScheduledTaskType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.experimental.SuperBuilder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.SQLRestriction;

import java.time.LocalDateTime;

/**
 * 予定の予約作成タスク（機能55 第一陣）。
 *
 * <p>親予定の開始時刻に紐づくスケジュールで、アンケート（{@link ScheduledTaskType#SURVEY}）または
 * 出欠（{@link ScheduledTaskType#ATTENDANCE}）を「予約」しておき、{@link #scheduledAt} 到来時に
 * 後続バッチが {@link #payloadJson} のスナップショットを元に実体を materialize する。</p>
 *
 * <p>UUIDv7 主キー（CLAUDE.md 原則6）。{@code scheduleId} / {@code scopeId} / {@code createdBy} は
 * クロスドメイン論理参照のため ID のみ保持し FK は持たない（原則1）。
 * {@code organizationId} をテナントキーとして保持する（原則7）。論理削除あり。</p>
 */
@Entity
@Table(name = "schedule_scheduled_tasks")
@SQLRestriction("deleted_at IS NULL")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
@EqualsAndHashCode(callSuper = true)
public class ScheduleScheduledTaskEntity extends UuidV7Entity {

    /** 親予定 schedules.id（クロスドメイン論理参照・FK なし）。 */
    @Column(name = "schedule_id", nullable = false)
    private Long scheduleId;

    /** テナントキー。team 予定なら所属組織の id（原則7）。 */
    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    /** スコープ種別（TEAM / ORGANIZATION）。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "scope_type", nullable = false, length = 20)
    private CalendarSyncScopeType scopeType;

    /** スコープ実体 ID（team_id または organization_id）。 */
    @Column(name = "scope_id", nullable = false)
    private Long scopeId;

    /** 予約タスク種別（SURVEY / ATTENDANCE）。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "task_type", nullable = false, length = 20)
    private ScheduledTaskType taskType;

    /** この時刻に materialize する。 */
    @Column(name = "scheduled_at", nullable = false)
    private LocalDateTime scheduledAt;

    /** タスクの状態。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private ScheduledTaskStatus status = ScheduledTaskStatus.PENDING;

    /** アンケート定義 / 出欠設定のスナップショット（JSON 文字列）。 */
    @Column(name = "payload_json", nullable = false, columnDefinition = "json")
    private String payloadJson;

    /** materialize 後の実体 id（event_survey / schedule_attendance 等）。未生成時は NULL。 */
    @Column(name = "materialized_entity_id")
    private Long materializedEntityId;

    /** materialize 試行回数。 */
    @Column(name = "attempt_count", nullable = false)
    @Builder.Default
    private Integer attemptCount = 0;

    /** 最終失敗理由（任意）。 */
    @Column(name = "last_error", length = 1000)
    private String lastError;

    /** 作成者 users.id（クロスドメイン論理参照・FK なし、任意）。 */
    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /** 論理削除日時。 */
    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (this.createdAt == null) {
            this.createdAt = now;
        }
        this.updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * materialize 成功としてマークする（PENDING → CREATED）。
     *
     * @param entityId 生成された実体の id
     */
    public void markCreated(Long entityId) {
        this.status = ScheduledTaskStatus.CREATED;
        this.materializedEntityId = entityId;
        this.lastError = null;
    }

    /**
     * materialize 失敗としてマークする（試行回数を加算し、理由を記録）。
     *
     * @param error 失敗理由
     */
    public void markFailed(String error) {
        this.status = ScheduledTaskStatus.FAILED;
        this.attemptCount = (this.attemptCount == null ? 0 : this.attemptCount) + 1;
        this.lastError = error;
    }

    /**
     * materialize 試行の失敗を記録する（機能55 第二陣）。
     *
     * <p>試行回数を加算し失敗理由を記録する。{@code attemptCount} が {@code maxAttempts} 以上に
     * 達したら FAILED 確定（打ち止め）、未満なら PENDING のまま据え置き（次回バッチで再試行可能）。</p>
     *
     * @param error       失敗理由
     * @param maxAttempts 最大試行回数（これ以上で FAILED 確定）
     */
    public void recordFailedAttempt(String error, int maxAttempts) {
        this.attemptCount = (this.attemptCount == null ? 0 : this.attemptCount) + 1;
        this.lastError = error;
        if (this.attemptCount >= maxAttempts) {
            this.status = ScheduledTaskStatus.FAILED;
        } else {
            this.status = ScheduledTaskStatus.PENDING;
        }
    }

    /**
     * 予約タスクを取り消す（→ CANCELLED）。
     */
    public void cancel() {
        this.status = ScheduledTaskStatus.CANCELLED;
    }

    /**
     * 論理削除する。
     */
    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
    }

    /**
     * 指定時刻時点で materialize 対象（PENDING かつ scheduledAt 到来済み）かを判定する。
     *
     * @param now 判定基準時刻
     * @return materialize すべき場合 true
     */
    public boolean isDue(LocalDateTime now) {
        return this.status == ScheduledTaskStatus.PENDING
                && this.scheduledAt != null
                && !this.scheduledAt.isAfter(now);
    }
}
