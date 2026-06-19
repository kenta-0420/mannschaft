package com.mannschaft.app.reservation.entity;

import com.mannschaft.app.common.BaseEntity;
import com.mannschaft.app.reservation.ApprovalMode;
import com.mannschaft.app.reservation.SlotStatus;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * 予約スロットエンティティ。チームが提供する予約可能な時間枠を管理する。
 */
@Entity
@Table(name = "reservation_slots")
@SQLRestriction("deleted_at IS NULL")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class ReservationSlotEntity extends BaseEntity {

    @Column(nullable = false)
    private Long teamId;

    private Long staffUserId;

    @Column(length = 200)
    private String title;

    @Column(nullable = false)
    private LocalDate slotDate;

    @Column(nullable = false)
    private LocalTime startTime;

    @Column(nullable = false)
    private LocalTime endTime;

    @Column(nullable = false)
    @Builder.Default
    private Integer bookedCount = 0;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private SlotStatus slotStatus = SlotStatus.AVAILABLE;

    @Column(columnDefinition = "JSON")
    private String recurrenceRule;

    private Long parentSlotId;

    @Column(nullable = false)
    @Builder.Default
    private Boolean isException = false;

    /**
     * 枠単位の承認モード上書き。
     *
     * <p>{@code null} = チーム既定（{@code reservation_policies}）に従う。
     * 値あり = この枠だけチーム既定を上書きする。
     * 承認モードの最終解決は {@code ReservationPolicyService.resolveApprovalMode(teamId, slot)} で行う。</p>
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "approval_mode")
    private ApprovalMode approvalMode;

    @Column(precision = 10, scale = 2)
    private BigDecimal price;

    @Column(length = 20)
    private String closedReason;

    @Column(columnDefinition = "TEXT")
    private String note;

    private Long createdBy;

    private LocalDateTime deletedAt;

    /**
     * 予約数をインクリメントする。
     */
    public void incrementBookedCount() {
        this.bookedCount++;
    }

    /**
     * 予約数をデクリメントする。
     */
    public void decrementBookedCount() {
        if (this.bookedCount > 0) {
            this.bookedCount--;
        }
    }

    /**
     * スロットを満席にする。
     */
    public void markFull() {
        this.slotStatus = SlotStatus.FULL;
    }

    /**
     * スロットを利用可能に戻す。
     */
    public void markAvailable() {
        this.slotStatus = SlotStatus.AVAILABLE;
    }

    /**
     * スロットをクローズする。
     *
     * @param reason クローズ理由
     */
    public void close(String reason) {
        this.slotStatus = SlotStatus.CLOSED;
        this.closedReason = reason;
    }

    /**
     * 担当者を変更する（部分更新）。
     *
     * @param staffUserId 担当者ユーザーID
     */
    public void changeStaffUser(Long staffUserId) {
        this.staffUserId = staffUserId;
    }

    /**
     * タイトルを変更する（部分更新）。
     *
     * @param title タイトル
     */
    public void changeTitle(String title) {
        this.title = title;
    }

    /**
     * 日付を変更する（部分更新）。
     *
     * @param slotDate スロット日付
     */
    public void changeSlotDate(LocalDate slotDate) {
        this.slotDate = slotDate;
    }

    /**
     * 時間帯を変更する（部分更新）。開始・終了時刻は対で更新する。
     *
     * @param startTime 開始時刻
     * @param endTime   終了時刻
     */
    public void changeTimeRange(LocalTime startTime, LocalTime endTime) {
        this.startTime = startTime;
        this.endTime = endTime;
    }

    /**
     * 価格を変更する（部分更新）。
     *
     * @param price 価格
     */
    public void changePrice(BigDecimal price) {
        this.price = price;
    }

    /**
     * メモを変更する（部分更新）。
     *
     * @param note メモ
     */
    public void changeNote(String note) {
        this.note = note;
    }

    /**
     * 枠単位の承認モード上書きを設定する（部分更新）。
     *
     * @param approvalMode 上書きする承認モード（{@code AUTO} / {@code MANUAL}）
     */
    public void changeApprovalMode(ApprovalMode approvalMode) {
        this.approvalMode = approvalMode;
    }

    /**
     * 枠単位の承認モード上書きを解除し、チーム既定に従う（{@code null}）状態へ戻す。
     */
    public void clearApprovalMode() {
        this.approvalMode = null;
    }

    /**
     * 繰り返しスロットかどうかを判定する。
     *
     * @return 繰り返しルールが設定されている場合 true
     */
    public boolean isRecurring() {
        return this.recurrenceRule != null;
    }

    /**
     * 利用可能かどうかを判定する。
     *
     * @return AVAILABLE ステータスの場合 true
     */
    public boolean isAvailable() {
        return this.slotStatus == SlotStatus.AVAILABLE;
    }

    /**
     * 論理削除を行う。
     */
    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
    }
}
