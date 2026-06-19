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
import lombok.experimental.SuperBuilder;
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
@SuperBuilder(toBuilder = true)
public class EventCheckinEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long eventId;

    /**
     * チケットID。QR/セルフチェックイン時は必須。
     * 点呼（ROLL_CALL / ROLL_CALL_BATCH）の場合はチケットが存在しないため null を許容する。
     * チケット式（STAFF_SCAN / SELF）は必須・チケットレス（ROLL_CALL / ROLL_CALL_BATCH / PROXY）は NULL を、
     * DB レベルの CHECK 制約 {@code chk_event_checkins_ticket_by_type}（V70.008）で保証する。
     */
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
    /**
     * 点呼チェックイン時の対象ユーザーID。
     * ROLL_CALL / ROLL_CALL_BATCH の場合のみ設定される（ticketId=null の代替）。
     */
    private Long rollCallUserId;

    private LocalDateTime checkoutAt;
    private LocalDateTime guardianCheckinNotifiedAt;
    private LocalDateTime guardianCheckoutNotifiedAt;

    @Column(length = 36)
    private String rollCallSessionId;

    private Integer lateArrivalMinutes;

    @Column(length = 30)
    private String absenceReason;

    /**
     * F03.10 代理チェックイン時の event_delegations.id。
     * 通常チェックイン時は NULL。代理チェックイン（checkinType = PROXY）時のみ設定される。
     * 二重チェックイン防止はアプリ層 + idx_ec_delegation での存在チェックで保証する（§3.5）。
     */
    @Column(name = "delegation_id")
    private java.util.UUID delegationId;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }

    /**
     * 点呼結果を上書き更新する（冪等処理の UPDATE 用）。
     *
     * @param checkinType        チェックイン種別（ROLL_CALL 等）
     * @param lateArrivalMinutes 遅刻分数（LATE の場合のみ）
     * @param absenceReason      欠席理由（ABSENT の場合のみ）
     */
    public void updateRollCallResult(CheckinType checkinType, Integer lateArrivalMinutes, String absenceReason) {
        this.checkinType = checkinType;
        this.lateArrivalMinutes = lateArrivalMinutes;
        this.absenceReason = absenceReason;
        this.checkedInAt = LocalDateTime.now();
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
