package com.mannschaft.app.schedule.entity;

import com.mannschaft.app.common.BaseEntity;
import com.mannschaft.app.schedule.AttendanceGenerationStatus;
import com.mannschaft.app.schedule.CommentOption;
import com.mannschaft.app.schedule.EventType;
import com.mannschaft.app.schedule.MinResponseRole;
import com.mannschaft.app.schedule.MinViewRole;
import com.mannschaft.app.schedule.ScheduleStatus;
import com.mannschaft.app.schedule.ScheduleVisibility;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.SQLRestriction;

import java.time.LocalDateTime;

/**
 * スケジュールエンティティ。チーム・組織・個人スコープのスケジュールを管理する。
 */
@Entity
@Table(name = "schedules")
@SQLRestriction("deleted_at IS NULL")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class ScheduleEntity extends BaseEntity {

    private Long teamId;

    private Long organizationId;

    private Long userId;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(length = 300)
    private String location;

    private Long venueId;

    @Column(nullable = false)
    private LocalDateTime startAt;

    private LocalDateTime endAt;

    @Column(nullable = false)
    @Builder.Default
    private Boolean allDay = false;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private EventType eventType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ScheduleVisibility visibility;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MinViewRole minViewRole;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private MinResponseRole minResponseRole;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ScheduleStatus status;

    @Column(nullable = false)
    @Builder.Default
    private Boolean attendanceRequired = false;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private AttendanceGenerationStatus attendanceStatus;

    private LocalDateTime attendanceDeadline;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private CommentOption commentOption;

    private Long eventCategoryId;

    private Long sourceScheduleId;

    private Short academicYear;

    private Long parentScheduleId;

    @Column(columnDefinition = "JSON")
    private String recurrenceRule;

    @Column(nullable = false)
    @Builder.Default
    private Boolean isException = false;

    @Column(length = 7)
    private String color;

    @Column(length = 255)
    private String googleCalendarEventId;

    private Long createdBy;

    private LocalDateTime deletedAt;

    /**
     * スケジュールをキャンセルする。
     */
    public void cancel() {
        this.status = ScheduleStatus.CANCELLED;
    }

    /**
     * スケジュールを完了にする。
     */
    public void complete() {
        this.status = ScheduleStatus.COMPLETED;
    }

    /**
     * 繰り返しスケジュールかどうかを判定する。
     *
     * @return 繰り返しルールが設定されている場合 true
     */
    public boolean isRecurring() {
        return this.recurrenceRule != null;
    }

    /**
     * 個人スコープかどうかを判定する。
     *
     * @return userId が設定されている場合 true
     */
    public boolean isPersonal() {
        return this.userId != null;
    }

    /**
     * チームスコープかどうかを判定する。
     *
     * @return teamId が設定されている場合 true
     */
    public boolean isTeamScope() {
        return this.teamId != null;
    }

    /**
     * 組織スコープかどうかを判定する。
     *
     * @return organizationId が設定されている場合 true
     */
    public boolean isOrganizationScope() {
        return this.organizationId != null;
    }

    /**
     * 論理削除を行う。
     */
    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
    }
}
