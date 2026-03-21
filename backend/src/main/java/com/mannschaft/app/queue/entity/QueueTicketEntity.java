package com.mannschaft.app.queue.entity;

import com.mannschaft.app.common.BaseEntity;
import com.mannschaft.app.queue.TicketSource;
import com.mannschaft.app.queue.TicketStatus;
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

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 順番待ちチケットエンティティ。個々の待ちチケットを管理する。
 */
@Entity
@Table(name = "queue_tickets")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class QueueTicketEntity extends BaseEntity {

    @Column(nullable = false)
    private Long categoryId;

    @Column(nullable = false)
    private Long counterId;

    @Column(nullable = false, length = 20)
    private String ticketNumber;

    private Long userId;

    @Column(length = 50)
    private String guestName;

    @Column(nullable = false)
    @Builder.Default
    private Short partySize = 1;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private TicketSource source;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private TicketStatus status = TicketStatus.WAITING;

    @Column(nullable = false)
    private Integer position;

    private Short estimatedWaitMinutes;

    private LocalDateTime calledAt;

    private LocalDateTime servingAt;

    private LocalDateTime completedAt;

    private LocalDateTime cancelledAt;

    private Long cancelledBy;

    private LocalDateTime noShowAt;

    private LocalDateTime holdUntil;

    @Column(nullable = false)
    @Builder.Default
    private Boolean holdUsed = false;

    private Short actualServiceMinutes;

    @Column(length = 300)
    private String note;

    private Long previousTicketId;

    @Column(nullable = false)
    private LocalDate issuedDate;

    /**
     * チケットを呼び出し状態にする。
     */
    public void call() {
        this.status = TicketStatus.CALLED;
        this.calledAt = LocalDateTime.now();
    }

    /**
     * チケットを対応中状態にする。
     */
    public void startServing() {
        this.status = TicketStatus.SERVING;
        this.servingAt = LocalDateTime.now();
    }

    /**
     * チケットを完了状態にする。
     *
     * @param actualServiceMinutes 実際の対応時間（分）
     */
    public void complete(Short actualServiceMinutes) {
        this.status = TicketStatus.COMPLETED;
        this.completedAt = LocalDateTime.now();
        this.actualServiceMinutes = actualServiceMinutes;
    }

    /**
     * チケットをキャンセルする。
     *
     * @param cancelledByUserId キャンセルしたユーザーID
     */
    public void cancel(Long cancelledByUserId) {
        this.status = TicketStatus.CANCELLED;
        this.cancelledAt = LocalDateTime.now();
        this.cancelledBy = cancelledByUserId;
    }

    /**
     * チケットを不在扱いにする。
     */
    public void markNoShow() {
        this.status = TicketStatus.NO_SHOW;
        this.noShowAt = LocalDateTime.now();
    }

    /**
     * 保留を設定する。
     *
     * @param holdUntil 保留期限
     */
    public void hold(LocalDateTime holdUntil) {
        this.holdUntil = holdUntil;
        this.holdUsed = true;
        this.status = TicketStatus.WAITING;
    }

    /**
     * 待ちポジションを更新する。
     *
     * @param newPosition 新しいポジション
     */
    public void updatePosition(int newPosition) {
        this.position = newPosition;
    }

    /**
     * 推定待ち時間を更新する。
     *
     * @param minutes 推定待ち時間（分）
     */
    public void updateEstimatedWaitMinutes(Short minutes) {
        this.estimatedWaitMinutes = minutes;
    }
}
