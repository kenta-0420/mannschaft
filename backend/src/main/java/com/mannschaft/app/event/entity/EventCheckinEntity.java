package com.mannschaft.app.event.entity;

import com.mannschaft.app.event.CheckinType;
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
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * イベントチェックインエンティティ。チェックイン記録を管理する。
 */
@Entity
@Table(name = "event_checkins")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class EventCheckinEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long eventId;

    @Column(nullable = false)
    private Long ticketId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private CheckinType checkinType = CheckinType.STAFF_SCAN;

    private Long checkedInBy;

    @Column(nullable = false)
    @Builder.Default
    private LocalDateTime checkedInAt = LocalDateTime.now();

    @Column(length = 300)
    private String note;

    private LocalDateTime createdAt;

    // F03.12 ケア対象者見守り通知・点呼機能
    private LocalDateTime checkoutAt;
    private LocalDateTime guardianCheckinNotifiedAt;
    private LocalDateTime guardianCheckoutNotifiedAt;

    @Column(length = 36)
    private String rollCallSessionId;

    private Integer lateArrivalMinutes;

    @Column(length = 30)
    private String absenceReason;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }

    /**
     * チェックアウト日時を記録する。
     */
    public void recordCheckout() {
        this.checkoutAt = LocalDateTime.now();
    }

    /**
     * 保護者へのチェックイン通知送信日時を記録する。
     */
    public void markGuardianCheckinNotified() {
        this.guardianCheckinNotifiedAt = LocalDateTime.now();
    }

    /**
     * 保護者へのチェックアウト通知送信日時を記録する。
     */
    public void markGuardianCheckoutNotified() {
        this.guardianCheckoutNotifiedAt = LocalDateTime.now();
    }
}
