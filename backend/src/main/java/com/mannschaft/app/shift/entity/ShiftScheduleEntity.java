package com.mannschaft.app.shift.entity;

import com.mannschaft.app.common.BaseEntity;
import com.mannschaft.app.shift.ShiftPeriodType;
import com.mannschaft.app.shift.ShiftScheduleStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.SQLRestriction;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * シフトスケジュールエンティティ。チーム単位のシフト期間を管理する。
 */
@Entity
@Table(name = "shift_schedules")
@SQLRestriction("deleted_at IS NULL")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class ShiftScheduleEntity extends BaseEntity {

    @Column(nullable = false)
    private Long teamId;

    @Column(nullable = false, length = 200)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private ShiftPeriodType periodType = ShiftPeriodType.WEEKLY;

    @Column(nullable = false)
    private LocalDate startDate;

    @Column(nullable = false)
    private LocalDate endDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private ShiftScheduleStatus status = ShiftScheduleStatus.DRAFT;

    private LocalDateTime requestDeadline;

    @Column(columnDefinition = "TEXT")
    private String note;

    private Long createdBy;

    private LocalDateTime publishedAt;

    private Long publishedBy;

    @Column(nullable = false)
    @Builder.Default
    private Boolean isReminderSent = false;

    @Column(nullable = false)
    @Builder.Default
    private Boolean isLowSubmissionAlerted = false;

    private LocalDateTime lastAutoTransitionAt;

    @Version
    @Column(nullable = false)
    @Builder.Default
    private Long version = 0L;

    private LocalDateTime deletedAt;

    /**
     * ステータスを希望収集中に遷移する。
     */
    public void startCollecting() {
        this.status = ShiftScheduleStatus.COLLECTING;
    }

    /**
     * ステータスを調整中に遷移する。
     */
    public void startAdjusting() {
        this.status = ShiftScheduleStatus.ADJUSTING;
    }

    /**
     * シフトを公開する。
     *
     * @param userId 公開操作者のユーザーID
     */
    public void publish(Long userId) {
        this.status = ShiftScheduleStatus.PUBLISHED;
        this.publishedAt = LocalDateTime.now();
        this.publishedBy = userId;
    }

    /**
     * シフトをアーカイブする。
     */
    public void archive() {
        this.status = ShiftScheduleStatus.ARCHIVED;
    }

    /**
     * 論理削除を行う。
     */
    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
    }

    /**
     * リマインダー送信済みに更新する。
     */
    public void markReminderSent() {
        this.isReminderSent = true;
    }

    /**
     * 低提出率アラート送信済みに更新する。
     */
    public void markLowSubmissionAlerted() {
        this.isLowSubmissionAlerted = true;
    }
}
