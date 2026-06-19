package com.mannschaft.app.school.entity;

import com.mannschaft.app.common.BaseEntity;
import com.mannschaft.app.common.EncryptedStringConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
public class FamilyAttendanceNoticeEntity extends BaseEntity {

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

    /** 詳細（健康情報配慮）— AES-256-GCM で暗号化して保存。 */
    @Convert(converter = EncryptedStringConverter.class)
    @Column(length = 2000)
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

    public FamilyNoticeStatus deriveStatus() {
        if (appliedToRecord) return FamilyNoticeStatus.APPLIED;
        if (acknowledgedBy != null) return FamilyNoticeStatus.ACKNOWLEDGED;
        return FamilyNoticeStatus.PENDING;
    }

    /**
     * 担任が連絡を確認済みにする（直接ミューテート）。
     *
     * <p>{@code toBuilder().build()} で作り直すと {@link com.mannschaft.app.common.BaseEntity}
     * の {@code id} が引き継がれず id=null の新インスタンスとなり、INSERT 化して行が重複する。
     * managed entity を直接書き換えることで JPA dirty checking が UPDATE を発行し id を保持する。</p>
     *
     * @param acknowledgedBy  担任のユーザーID
     * @param acknowledgedAt  確認日時
     */
    public void acknowledge(Long acknowledgedBy, java.time.LocalDateTime acknowledgedAt) {
        this.acknowledgedBy = acknowledgedBy;
        this.acknowledgedAt = acknowledgedAt;
    }

    /**
     * 出欠レコードへの反映フラグを true にする（直接ミューテート）。
     *
     * <p>id 保持で UPDATE するために直接代入する。{@link #acknowledge} と同じ理由による。</p>
     */
    public void markAppliedToRecord() {
        this.appliedToRecord = true;
    }
}
