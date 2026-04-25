package com.mannschaft.app.event.entity;

import com.mannschaft.app.common.BaseEntity;
import com.mannschaft.app.event.EventScopeType;
import com.mannschaft.app.event.EventStatus;
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

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * イベントエンティティ。チーム・組織スコープのイベントを管理する。
 */
@Entity
@Table(name = "events")
@SQLRestriction("deleted_at IS NULL")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class EventEntity extends BaseEntity {

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private EventScopeType scopeType;

    @Column(nullable = false)
    private Long scopeId;

    private Long scheduleId;

    @Column(nullable = false, length = 100)
    private String slug;

    @Column(length = 200)
    private String subtitle;

    @Column(columnDefinition = "TEXT")
    private String summary;

    @Column(length = 300)
    private String coverImageKey;

    @Column(length = 200)
    private String venueName;

    @Column(length = 500)
    private String venueAddress;

    @Column(precision = 10, scale = 7)
    private BigDecimal venueLatitude;

    @Column(precision = 10, scale = 7)
    private BigDecimal venueLongitude;

    @Column(columnDefinition = "TEXT")
    private String venueAccessInfo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private EventStatus status = EventStatus.DRAFT;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    @Builder.Default
    private EventVisibility visibility = EventVisibility.MEMBERS_ONLY;

    private LocalDateTime registrationStartsAt;

    private LocalDateTime registrationEndsAt;

    private Integer maxCapacity;

    @Column(nullable = false)
    @Builder.Default
    private Boolean isApprovalRequired = false;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private EventAttendanceMode attendanceMode = EventAttendanceMode.REGISTRATION;

    private Long preSurveyId;

    private Long postSurveyId;

    private Long workflowRequestId;

    @Column(length = 200)
    private String ogpTitle;

    @Column(length = 500)
    private String ogpDescription;

    @Column(length = 300)
    private String ogpImageKey;

    @Column(nullable = false)
    @Builder.Default
    private Integer registrationCount = 0;

    @Column(nullable = false)
    @Builder.Default
    private Integer checkinCount = 0;

    private Long createdBy;

    @Version
    private Long version;

    private LocalDateTime deletedAt;

    // F03.12 解散通知・リマインド
    private LocalDateTime dismissalNotificationSentAt;
    private Long dismissalNotifiedBy;

    @Column(nullable = false)
    @Builder.Default
    private Byte organizerReminderSentCount = 0;

    private LocalDateTime lastOrganizerReminderAt;

    /**
     * イベントを公開する。
     */
    public void publish() {
        this.status = EventStatus.PUBLISHED;
    }

    /**
     * イベントをキャンセルする。
     */
    public void cancel() {
        this.status = EventStatus.CANCELLED;
    }

    /**
     * イベントを完了にする。
     */
    public void complete() {
        this.status = EventStatus.COMPLETED;
    }

    /**
     * 参加登録を開始する。
     */
    public void openRegistration() {
        this.status = EventStatus.REGISTRATION_OPEN;
    }

    /**
     * 参加登録を締め切る。
     */
    public void closeRegistration() {
        this.status = EventStatus.REGISTRATION_CLOSED;
    }

    /**
     * 参加登録数をインクリメントする。
     */
    public void incrementRegistrationCount() {
        this.registrationCount++;
    }

    /**
     * チェックイン数をインクリメントする。
     */
    public void incrementCheckinCount() {
        this.checkinCount++;
    }

    /**
     * 論理削除を行う。
     */
    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
    }

    /**
     * 解散通知を送信済みとして記録する。
     *
     * @param notifiedByUserId 解散通知を送信したユーザーID
     */
    public void recordDismissal(Long notifiedByUserId) {
        this.dismissalNotificationSentAt = LocalDateTime.now();
        this.dismissalNotifiedBy = notifiedByUserId;
    }

    /**
     * 主催者向けリマインダー送信回数をインクリメントする。
     */
    public void incrementOrganizerReminder() {
        this.organizerReminderSentCount = (byte) (this.organizerReminderSentCount + 1);
        this.lastOrganizerReminderAt = LocalDateTime.now();
    }
}
