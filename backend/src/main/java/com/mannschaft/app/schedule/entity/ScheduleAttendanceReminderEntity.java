package com.mannschaft.app.schedule.entity;

import com.mannschaft.app.schedule.ReminderKind;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.experimental.SuperBuilder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 出欠リマインダーエンティティ。スケジュールの出欠リマインド通知を管理する。
 *
 * <p>機能55 第一陣で相対指定（開始N分前）に対応。{@link #reminderKind} が
 * {@link ReminderKind#ABSOLUTE} なら {@link #remindAt}（絶対日時）を、
 * {@link ReminderKind#RELATIVE} なら {@link #remindBeforeMinutes}（開始N分前）を使用する。</p>
 */
@Entity
@Table(name = "schedule_attendance_reminders")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
public class ScheduleAttendanceReminderEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long scheduleId;

    /**
     * 絶対日時（{@link ReminderKind#ABSOLUTE} 時に使用）。
     * 相対指定（{@link ReminderKind#RELATIVE}）時は materialize 前に未確定のため NULL を許容する。
     */
    private LocalDateTime remindAt;

    /** 相対指定：開始N分前（{@link ReminderKind#RELATIVE} 時に使用）。 */
    private Integer remindBeforeMinutes;

    /** リマインダー指定方式。既定は後方互換のため ABSOLUTE。 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    @Builder.Default
    private ReminderKind reminderKind = ReminderKind.ABSOLUTE;

    @Column(nullable = false)
    @Builder.Default
    private Boolean isSent = false;

    private LocalDateTime sentAt;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }

    /**
     * リマインダーを送信済みにする。
     */
    public void markAsSent() {
        this.isSent = true;
        this.sentAt = LocalDateTime.now();
    }

    /**
     * 実際にリマインドすべき絶対日時を解決する。
     *
     * <p>{@link ReminderKind#RELATIVE} の場合は親予定の開始時刻から {@link #remindBeforeMinutes} 分を
     * 差し引いた時刻を、{@link ReminderKind#ABSOLUTE} の場合は {@link #remindAt} をそのまま返す。</p>
     *
     * @param startAt 親予定の開始時刻（相対指定時のみ参照）
     * @return リマインドすべき絶対日時。解決不能な場合は {@code null}
     */
    public LocalDateTime effectiveRemindAt(LocalDateTime startAt) {
        if (this.reminderKind == ReminderKind.RELATIVE) {
            if (startAt == null || this.remindBeforeMinutes == null) {
                return null;
            }
            return startAt.minusMinutes(this.remindBeforeMinutes);
        }
        return this.remindAt;
    }
}
