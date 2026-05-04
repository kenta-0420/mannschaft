package com.mannschaft.app.shiftbudget.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.mannschaft.app.shiftbudget.entity.ShiftBudgetFailedEventEntity;
import lombok.Builder;

import java.time.LocalDateTime;

/**
 * F08.7 Phase 10-β: 失敗イベントレスポンス DTO（管理 API）。
 *
 * <p>{@code payload} は再実行用の元データだが、PII 漏洩リスク回避と
 * レスポンスサイズ抑制のため省略する。詳細は監査ログ + DB 直接参照に委ねる。</p>
 */
@Builder
public record FailedEventResponse(

        @JsonProperty("id")
        Long id,

        @JsonProperty("organization_id")
        Long organizationId,

        @JsonProperty("event_type")
        String eventType,

        @JsonProperty("source_id")
        Long sourceId,

        @JsonProperty("error_message")
        String errorMessage,

        @JsonProperty("retry_count")
        Integer retryCount,

        @JsonProperty("last_retried_at")
        LocalDateTime lastRetriedAt,

        @JsonProperty("status")
        String status,

        @JsonProperty("created_at")
        LocalDateTime createdAt,

        @JsonProperty("updated_at")
        LocalDateTime updatedAt
) {
    public static FailedEventResponse from(ShiftBudgetFailedEventEntity entity) {
        return FailedEventResponse.builder()
                .id(entity.getId())
                .organizationId(entity.getOrganizationId())
                .eventType(entity.getEventType() != null ? entity.getEventType().name() : null)
                .sourceId(entity.getSourceId())
                .errorMessage(entity.getErrorMessage())
                .retryCount(entity.getRetryCount())
                .lastRetriedAt(entity.getLastRetriedAt())
                .status(entity.getStatus() != null ? entity.getStatus().name() : null)
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }
}
