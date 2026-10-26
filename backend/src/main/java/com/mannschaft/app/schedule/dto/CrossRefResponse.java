package com.mannschaft.app.schedule.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * クロスリファレンスレスポンスDTO。チーム・組織間招待の状態を返す。
 */
@Builder(toBuilder = true)
@Getter
public class CrossRefResponse {

    Long              id;
    Long              sourceScheduleId;
    CrossRefTargetDto target;  // targetType, targetId, targetScheduleId, status
    CrossRefAuditDto  audit;   // invitedBy, message, createdAt, respondedAt

    public record CrossRefTargetDto(String targetType, Long targetId, Long targetScheduleId,
                                    String status) {
    }

    public record CrossRefAuditDto(Long invitedBy, String message, LocalDateTime createdAt,
                                   LocalDateTime respondedAt) {
    }
}
