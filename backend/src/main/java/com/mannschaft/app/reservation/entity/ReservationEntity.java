package com.mannschaft.app.reservation.entity;

import com.mannschaft.app.common.BaseEntity;
import com.mannschaft.app.reservation.CancelledBy;
import com.mannschaft.app.reservation.ReservationStatus;
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
 * 予約エンティティ。ユーザーによる予約情報を管理する。
 */
@Entity
@Table(name = "reservations")
@SQLRestriction("deleted_at IS NULL")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class ReservationEntity extends BaseEntity {

    @Column(nullable = false)
    private Long reservationSlotId;

    @Column(nullable = false)
    private Long lineId;

    @Column(nullable = false)
    private Long teamId;

    @Column(nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private ReservationStatus status = ReservationStatus.PENDING;

    @Column(nullable = false)
    @Builder.Default
    private LocalDateTime bookedAt = LocalDateTime.now();

    private LocalDateTime confirmedAt;

    private LocalDateTime cancelledAt;

    @Column(length = 500)
    private String cancelReason;

    @Enumerated(EnumType.STRING)
    @Column(length = 10)
    private CancelledBy cancelledBy;

    private LocalDateTime completedAt;

    @Column(length = 500)
    private String userNote;

    @Column(length = 500)
    private String adminNote;

    private LocalDateTime deletedAt;

    /**
     * 予約を確定する。
     */
    public void confirm() {
        this.status = ReservationStatus.CONFIRMED;
        this.confirmedAt = LocalDateTime.now();
    }

    /**
     * 予約をキャンセルする。
     *
     * @param reason      キャンセル理由
     * @param cancelledBy キャンセル実行者
     */
    public void cancel(String reason, CancelledBy cancelledBy) {
        this.status = ReservationStatus.CANCELLED;
        this.cancelReason = reason;
        this.cancelledBy = cancelledBy;
        this.cancelledAt = LocalDateTime.now();
    }

    /**
     * 予約を完了する。
     */
    public void complete() {
        this.status = ReservationStatus.COMPLETED;
        this.completedAt = LocalDateTime.now();
    }

    /**
     * ノーショーとしてマークする。
     */
    public void noShow() {
        this.status = ReservationStatus.NO_SHOW;
    }

    /**
     * スロットを変更（リスケジュール）する。
     *
     * @param newSlotId 新しいスロットID
     */
    public void reschedule(Long newSlotId) {
        this.reservationSlotId = newSlotId;
        this.status = ReservationStatus.PENDING;
        this.confirmedAt = null;
    }

    /**
     * 管理者メモを更新する。
     *
     * @param note 管理者メモ
     */
    public void updateAdminNote(String note) {
        this.adminNote = note;
    }

    /**
     * 確定可能かどうかを判定する。
     *
     * @return PENDING ステータスの場合 true
     */
    public boolean isConfirmable() {
        return this.status == ReservationStatus.PENDING;
    }

    /**
     * キャンセル可能かどうかを判定する。
     *
     * @return PENDING または CONFIRMED ステータスの場合 true
     */
    public boolean isCancellable() {
        return this.status == ReservationStatus.PENDING
                || this.status == ReservationStatus.CONFIRMED;
    }

    /**
     * 論理削除を行う。
     */
    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
    }
}
