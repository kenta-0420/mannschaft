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
 * 臨時休業エンティティ。一括通知の送信履歴を管理する。
 *
 * <p>部分時間帯休業の表現:
 * <ul>
 *   <li>{@code startTime} と {@code endTime} がいずれも NULL → 終日休業</li>
 *   <li>両方セット → 指定時間帯のみの休業（例: 9:00〜11:00）。{@code startDate}〜{@code endDate} の各日に同じ時間帯が適用される</li>
 * </ul>
 */
@Entity
@Table(name = "emergency_closures")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class EmergencyClosureEntity extends BaseEntity {

    @Column(nullable = false)
    private Long teamId;

    @Column(nullable = false)
    private LocalDate startDate;

    @Column(nullable = false)
    private LocalDate endDate;

    /** 部分時間帯休業の開始時刻。NULLなら終日休業 */
    @Column
    private LocalTime startTime;

    /** 部分時間帯休業の終了時刻。NULLなら終日休業 */
    @Column
    private LocalTime endTime;

    /** 休業理由（先生体調不良 など） */
    @Column(nullable = false, length = 200)
    private String reason;

    /** メール件名 */
    @Column(nullable = false, length = 200)
    private String subject;

    /** メール本文 */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String messageBody;

    /** 送信件数 */
    @Column(nullable = false)
    @Builder.Default
    private Integer sentCount = 0;

    /** 対象予約を自動キャンセルしたか */
    @Column(nullable = false)
    @Builder.Default
    private Boolean cancelReservations = false;

    @Column(nullable = false)
    private Long createdBy;
}
