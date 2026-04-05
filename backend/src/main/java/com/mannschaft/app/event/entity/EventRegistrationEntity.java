package com.mannschaft.app.event.entity;

import com.mannschaft.app.common.BaseEntity;
import com.mannschaft.app.event.RegistrationStatus;
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

import java.time.LocalDateTime;

/**
 * イベント参加登録エンティティ。ユーザーまたはゲストの参加登録を管理する。
 */
@Entity
@Table(name = "event_registrations")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class EventRegistrationEntity extends BaseEntity {

    @Column(nullable = false)
    private Long eventId;

    private Long userId;

    @Column(nullable = false)
    private Long ticketTypeId;

    @Column(length = 100)
    private String guestName;

    @Column(length = 255)
    private String guestEmail;

    @Column(length = 50)
    private String guestPhone;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private RegistrationStatus status = RegistrationStatus.PENDING;

    @Column(nullable = false)
    @Builder.Default
    private Integer quantity = 1;

    @Column(length = 500)
    private String note;

    private Long approvedBy;

    private LocalDateTime approvedAt;

    private LocalDateTime cancelledAt;

    @Column(length = 500)
    private String cancelReason;

    private Long inviteTokenId;

    /**
     * 参加登録を承認する。
     *
     * @param approvedByUserId 承認者のユーザーID
     */
    public void approve(Long approvedByUserId) {
        this.status = RegistrationStatus.APPROVED;
        this.approvedBy = approvedByUserId;
        this.approvedAt = LocalDateTime.now();
    }

    /**
     * 参加登録を却下する。
     *
     * @param approvedByUserId 却下者のユーザーID
     */
    public void reject(Long approvedByUserId) {
        this.status = RegistrationStatus.REJECTED;
        this.approvedBy = approvedByUserId;
        this.approvedAt = LocalDateTime.now();
    }

    /**
     * 参加登録をキャンセルする。
     *
     * @param reason キャンセル理由
     */
    public void cancel(String reason) {
        this.status = RegistrationStatus.CANCELLED;
        this.cancelReason = reason;
        this.cancelledAt = LocalDateTime.now();
    }

    /**
     * キャンセル待ちに変更する。
     */
    public void waitlist() {
        this.status = RegistrationStatus.WAITLISTED;
    }
}
