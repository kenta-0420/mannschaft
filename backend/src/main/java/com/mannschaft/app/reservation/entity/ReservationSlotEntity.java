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
import lombok.Builder;
import lombok.experimental.SuperBuilder;
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
@SuperBuilder(toBuilder = true)
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

    /**
     * 予約枠の定員。{@code booked_count} がこの値に達すると満席（{@link SlotStatus#FULL}）になる。
     *
     * <p>既定は 1（＝美容院の 1:1 指名など、同一枠 1 名のみ受付）。
     * {@code @Builder.Default} で NULL 挿入を防ぐ（toBuilder 更新破壊キャンペーン §注意）。</p>
     */
    @Column(nullable = false)
    @Builder.Default
    private Integer capacity = 1;

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
     * 予約数をインクリメントし、定員に達したら満席（FULL）にする。
     *
     * <p>ドメイン整合のための単体（非並行）ロジック。実際の予約作成時の並行制御は
     * {@code ReservationSlotRepository.incrementBookedCountIfAvailable}（条件付きアトミック
     * UPDATE）が担い、複数ユーザーの同時予約でも {@code capacity} を超えないことを保証する。
     * 両者は「{@code bookedCount + 1 >= capacity} で FULL」という同一ルールを表す。</p>
     */
    public void incrementBookedCount() {
        this.bookedCount++;
        if (this.capacity != null && this.bookedCount >= this.capacity) {
            this.slotStatus = SlotStatus.FULL;
        }
    }

    /**
     * 予約数をデクリメントし、満席が解消されたら利用可能（AVAILABLE）へ戻す。
     *
     * <p>{@link SlotStatus#CLOSED}（スタッフ操作による受付終了）は据え置く。FULL のみ復帰させる。</p>
     */
    public void decrementBookedCount() {
        if (this.bookedCount > 0) {
            this.bookedCount--;
        }
        if (this.slotStatus == SlotStatus.FULL
                && this.capacity != null && this.bookedCount < this.capacity) {
            this.slotStatus = SlotStatus.AVAILABLE;
        }
    }

    /**
     * 定員を変更する（部分更新）。変更後の定員と予約数の関係で満席/空きを再評価する。
     *
     * <p>定員を減らして {@code bookedCount >= capacity} になれば FULL 化し、
     * 逆に増やして {@code bookedCount < capacity} になれば（FULL だった枠を）AVAILABLE へ戻す。
     * CLOSED は据え置く。</p>
     *
     * @param capacity 新しい定員（1 以上）
     */
    public void changeCapacity(Integer capacity) {
        this.capacity = capacity;
        if (capacity == null) {
            return;
        }
        if (this.slotStatus == SlotStatus.AVAILABLE && this.bookedCount >= capacity) {
            this.slotStatus = SlotStatus.FULL;
        } else if (this.slotStatus == SlotStatus.FULL && this.bookedCount < capacity) {
            this.slotStatus = SlotStatus.AVAILABLE;
        }
    }

    /**
     * 満席かどうかを判定する（予約数が定員に達している）。
     *
     * @return {@code bookedCount >= capacity} の場合 true
     */
    public boolean isFull() {
        return this.capacity != null && this.bookedCount >= this.capacity;
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
