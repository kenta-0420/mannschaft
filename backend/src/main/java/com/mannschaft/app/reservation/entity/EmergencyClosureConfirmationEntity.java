package com.mannschaft.app.reservation.entity;

import com.mannschaft.app.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 臨時休業確認追跡エンティティ。患者が臨時休業通知を確認したかどうかを管理する。
 */
@Entity
@Table(name = "emergency_closure_confirmations")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class EmergencyClosureConfirmationEntity extends BaseEntity {

    @Column(nullable = false)
    private Long emergencyClosureId;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false)
    private Long reservationId;

    @Column(nullable = false)
    private LocalDateTime appointmentAt;

    /** 患者が確認した日時。null の場合は未確認。 */
    @Column
    private LocalDateTime confirmedAt;

    /** リマインダーを送信者へ送った日時。null の場合は未送信。 */
    @Column
    private LocalDateTime reminderSentAt;

    /** 患者本人への再リマインドを送った日時（予約3時間前）。null の場合は未送信。 */
    @Column
    private LocalDateTime patientReminderSentAt;

    /**
     * 確認済みとしてマークする。
     */
    public void confirm() {
        this.confirmedAt = LocalDateTime.now();
    }

    /**
     * 確認済みかどうかを返す。
     */
    public boolean isConfirmed() {
        return this.confirmedAt != null;
    }

    /**
     * 送信者へのリマインダー送信済みとしてマークする。
     */
    public void markReminderSent() {
        this.reminderSentAt = LocalDateTime.now();
    }

    /**
     * 患者本人への再リマインド送信済みとしてマークする。
     */
    public void markPatientReminderSent() {
        this.patientReminderSentAt = LocalDateTime.now();
    }
}
