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

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * 予約ブロック時間エンティティ。チームの臨時休業・休憩時間を管理する。
 */
@Entity
@Table(name = "reservation_blocked_times")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class ReservationBlockedTimeEntity extends BaseEntity {

    @Column(nullable = false)
    private Long teamId;

    @Column(nullable = false)
    private LocalDate blockedDate;

    private LocalTime startTime;

    private LocalTime endTime;

    @Column(length = 200)
    private String reason;

    private Long createdBy;

    /**
     * ブロック時間を更新する。
     *
     * @param blockedDate ブロック日
     * @param startTime   開始時刻
     * @param endTime     終了時刻
     * @param reason      理由
     */
    public void update(LocalDate blockedDate, LocalTime startTime, LocalTime endTime, String reason) {
        this.blockedDate = blockedDate;
        this.startTime = startTime;
        this.endTime = endTime;
        this.reason = reason;
    }
}
