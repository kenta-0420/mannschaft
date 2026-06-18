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

    /** カスタム公開範囲テンプレートID (F01.7)。visibility = CUSTOM_TEMPLATE の場合のみ使用 */
    @Column(name = "visibility_template_id")
    private Long visibilityTemplateId;

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

    /**
     * 出欠確認の配信母集団にサポーター（応援者）を含めるか。
     * (B) 組織→参加チーム配信 案C フェーズA 隊A で追加。
     * 既定 false（組織配信時はサポーター除外）。値を使った母集団絞り込みの配線は後続隊。
     */
    @Column(name = "include_supporters", nullable = false, columnDefinition = "TINYINT(1) DEFAULT 0")
    @Builder.Default
    private Boolean includeSupporters = false;

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

    @Column(name = "linked_todo_id")
    private Long linkedTodoId;

    @Column(nullable = false)
    @Builder.Default
    private Boolean isException = false;

    @Column(length = 7)
    private String color;

    /**
     * F03.10 代理出席: 代理出席を許可するか。
     * TRUE のスケジュールのみ代理指定 API が有効になる。
     */
    @Column(name = "allow_proxy_attendance", nullable = false,
            columnDefinition = "BOOLEAN NOT NULL DEFAULT FALSE")
    @Builder.Default
    private Boolean allowProxyAttendance = false;

    /**
     * F03.10 代理出席: 代理人の承認不要（TRUE = 指定時に即 ACCEPTED）。
     */
    @Column(name = "is_proxy_auto_accept", nullable = false,
            columnDefinition = "BOOLEAN NOT NULL DEFAULT FALSE")
    @Builder.Default
    private Boolean isProxyAutoAccept = false;

    @Column(length = 255)
    private String googleCalendarEventId;

    /**
     * F03.15 等の外部参照 JSON（idempotency 用）。
     * 例: {"source":"F03.15","timetable_change_id":1,"personal_timetable_slot_id":2}
     */
    @Column(name = "external_ref", columnDefinition = "TEXT")
    private String externalRef;

    private Long createdBy;

    private LocalDateTime deletedAt;

    /**
     * external_ref を更新するためのセッター（リスナー経由の冪等更新用）。
     */
    public void setExternalRef(String externalRef) {
        this.externalRef = externalRef;
    }

    /**
     * リスナー側からタイトル・時刻・色などを更新するためのセッター群。
     */
    public void updateScheduleFields(String title, String description, String location,
                                      LocalDateTime startAt, LocalDateTime endAt, String color) {
        this.title = title;
        this.description = description;
        this.location = location;
        this.startAt = startAt;
        this.endAt = endAt;
        this.color = color;
    }

    /**
     * 個人スケジュールのPATCH更新を適用する。
     * toBuilder() は BaseEntity の id を継承しないため、直接フィールド変更方式を採用する。
     * null の項目はスキップ（PATCHセマンティクス）。
     */
    public void applyPersonalScheduleUpdate(String title, String description, String location,
                                             LocalDateTime startAt, LocalDateTime endAt,
                                             Boolean allDay, EventType eventType, String color) {
        if (title != null) this.title = title;
        if (description != null) this.description = description;
        if (location != null) this.location = location;
        if (startAt != null) this.startAt = startAt;
        if (endAt != null) this.endAt = endAt;
        if (allDay != null) this.allDay = allDay;
        if (eventType != null) this.eventType = eventType;
        if (color != null) this.color = color;
    }

    /**
     * 繰り返しスケジュールの例外フラグを立てる（THIS_ONLY更新時に使用）。
     * toBuilder().isException(true).build() は BaseEntity の id を引き継がないため、
     * このメソッドで直接フィールドを変更する。
     */
    public void markAsException() {
        this.isException = true;
    }

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
