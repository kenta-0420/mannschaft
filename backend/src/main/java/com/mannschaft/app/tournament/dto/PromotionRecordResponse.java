package com.mannschaft.app.tournament.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * 昇降格記録レスポンスDTO。
 */
@Builder(toBuilder = true)
@Getter
public class PromotionRecordResponse {

    private Long id;
    private PromotionRecordContextDto context;
    private PromotionRecordDetailDto detail;
    private PromotionRecordExecutionDto execution;

    public record PromotionRecordContextDto(Long tournamentId, Long teamId) {}

    public record PromotionRecordDetailDto(
            Long fromDivisionId, Long toDivisionId, String type,
            Integer finalRank, String reason) {}

    public record PromotionRecordExecutionDto(Long executedBy, LocalDateTime executedAt) {}
}
