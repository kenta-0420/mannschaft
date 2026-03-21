package com.mannschaft.app.reservation.entity;

import com.mannschaft.app.common.BaseEntity;
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
