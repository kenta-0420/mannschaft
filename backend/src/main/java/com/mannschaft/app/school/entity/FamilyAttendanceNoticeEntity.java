package com.mannschaft.app.school.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/** 保護者からの欠席・遅刻連絡。担任への通知起点。 */
@Entity
@Table(name = "family_attendance_notices")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class FamilyAttendanceNoticeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long teamId;

    @Column(nullable = false)
    private Long studentUserId;

    /** 連絡送信者（保護者） */
    @Column(nullable = false)
    private Long submitterUserId;

    @Column(nullable = false)
    private LocalDate attendanceDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 15)
    private FamilyNoticeType noticeType;

    @Enumerated(EnumType.STRING)
    @Column(length = 25)
    private AbsenceReason reason;

    /** 詳細（健康情報配慮） */
    @Column(length = 1000)
    private String reasonDetail;

    /** 遅刻時の到着予定 */
    private LocalTime expectedArrivalTime;

    /** 早退時の早退予定 */
    private LocalTime expectedLeaveTime;

    /** 添付ファイルキー配列（JSON） */
    @Column(columnDefinition = "JSON")
    private String attachedFileKeys;

    /** 担任が確認した user_id */
    private Long acknowledgedBy;

    private LocalDateTime acknowledgedAt;

    /** 出欠レコードに反映済みか */
    @Column(nullable = false)
    @Builder.Default
    private Boolean appliedToRecord = false;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

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

    public FamilyNoticeStatus deriveStatus() {
        if (appliedToRecord) return FamilyNoticeStatus.APPLIED;
        if (acknowledgedBy != null) return FamilyNoticeStatus.ACKNOWLEDGED;
        return FamilyNoticeStatus.PENDING;
    }
}
