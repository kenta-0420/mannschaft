package com.mannschaft.app.shift.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * シフトスケジュールレスポンスDTO。
 */
@Builder(toBuilder = true)
@Getter
public class ShiftScheduleResponse {

    Long id;
    Long teamId;

    ShiftContentDto  content;  // title, periodType, note
    ShiftPeriodDto   period;   // startDate, endDate, requestDeadline
    ShiftStatusDto   status;   // status, publishedAt, publishedBy
    ShiftAuditDto    audit;    // createdBy, createdAt, updatedAt

    public record ShiftContentDto(String title, String periodType, String note) {}
    public record ShiftPeriodDto(LocalDate startDate, LocalDate endDate, LocalDateTime requestDeadline) {}
    public record ShiftStatusDto(String status, LocalDateTime publishedAt, Long publishedBy) {}
    public record ShiftAuditDto(Long createdBy, LocalDateTime createdAt, LocalDateTime updatedAt) {}
}
