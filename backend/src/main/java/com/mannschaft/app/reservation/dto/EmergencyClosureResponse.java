package com.mannschaft.app.reservation.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * 臨時休業一括通知レスポンス。
 *
 * <p>{@code startTime} / {@code endTime} が両方 null の場合は終日休業、両方値があれば部分時間帯休業を表す。
 */
@Getter
@Builder
@AllArgsConstructor
public class EmergencyClosureResponse {

    private Long id;
    private Long teamId;
    private LocalDate startDate;
    private LocalDate endDate;

    /** 部分時間帯休業の開始時刻。終日休業の場合は null */
    @JsonFormat(pattern = "HH:mm")
    private LocalTime startTime;

    /** 部分時間帯休業の終了時刻。終日休業の場合は null */
    @JsonFormat(pattern = "HH:mm")
    private LocalTime endTime;

    private String reason;
    private String subject;
    private String messageBody;
    private int sentCount;
    private boolean cancelReservations;
    private Long createdBy;
    private LocalDateTime createdAt;
}
