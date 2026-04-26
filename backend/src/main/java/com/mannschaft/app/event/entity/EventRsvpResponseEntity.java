package com.mannschaft.app.event.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * イベントRSVP回答エンティティ。
 * attendance_mode=RSVP のイベントに対するメンバーの出欠回答を管理する。
 */
@Entity
@Table(
        name = "event_rsvp_responses",
        uniqueConstraints = @UniqueConstraint(columnNames = {"event_id", "user_id"})
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class EventRsvpResponseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long eventId;

    @Column(nullable = false)
    private Long userId;

    /**
     * 出欠回答。ATTENDING / NOT_ATTENDING / MAYBE / UNDECIDED
     */
    @Column(nullable = false, length = 20)
    @Builder.Default
    private String response = "UNDECIDED";

    @Column(length = 500)
    private String comment;

    private LocalDateTime respondedAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    // F03.12 ケア対象者見守り通知・事前遅刻連絡
    private LocalDateTime guardianRsvpNotifiedAt;
    private Integer expectedArrivalMinutesLate;

    @Column(length = 30)
    private String advanceAbsenceReason;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * 回答を更新する。
     *
     * @param response  新しい回答値
     * @param comment   コメント（任意）
     */
    public void updateResponse(String response, String comment) {
        this.response = response;
        this.comment = comment;
        this.respondedAt = LocalDateTime.now();
    }

    /**
     * 保護者へのRSVP確認通知送信日時を記録する。
     */
    public void markGuardianRsvpNotified() {
        this.guardianRsvpNotifiedAt = LocalDateTime.now();
    }

    /**
     * 遅刻予定を記録する。
     *
     * @param minutes 遅刻予定分数
     */
    public void recordLateNotice(int minutes) {
        this.expectedArrivalMinutesLate = minutes;
        this.respondedAt = LocalDateTime.now();
    }

    /**
     * 事前欠席連絡を記録する。
     *
     * @param reason 欠席理由
     */
    public void recordAbsenceNotice(String reason) {
        this.response = "NOT_ATTENDING";
        this.advanceAbsenceReason = reason;
        this.respondedAt = LocalDateTime.now();
    }
}
