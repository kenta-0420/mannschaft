package com.mannschaft.app.reservation.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * 臨時休業通知プレビューレスポンス。送信前に影響を受ける予約を確認するために使用する。
 *
 * <p>{@code startTime} / {@code endTime} が両方 null の場合は終日休業、両方値があれば部分時間帯休業のプレビュー。
 */
@Getter
@Builder
@AllArgsConstructor
public class EmergencyClosurePreviewResponse {

    private LocalDate startDate;
    private LocalDate endDate;

    /** 部分時間帯休業の開始時刻。終日休業の場合は null */
    @JsonFormat(pattern = "HH:mm")
    private LocalTime startTime;

    /** 部分時間帯休業の終了時刻。終日休業の場合は null */
    @JsonFormat(pattern = "HH:mm")
    private LocalTime endTime;

    private int affectedCount;
    private List<AffectedReservation> affectedReservations;

    @Getter
    @Builder
    @AllArgsConstructor
    public static class AffectedReservation {
        private Long reservationId;
        private Long userId;
        private String userDisplayName;
        private String userEmail;
        private LocalDate slotDate;
        private LocalTime startTime;
        private LocalTime endTime;
        private String status;
    }
}
