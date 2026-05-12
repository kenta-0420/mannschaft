package com.mannschaft.app.repairplan.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Getter;

import java.util.UUID;

/**
 * 修繕計画ダッシュボード「次5年の修繕予定」ペインの 1 行 DTO。
 *
 * <p>{@code RepairPlanItem} のうち、現在ユーザーに対する表示に必要な最小フィールドを抽出したもの。
 * 設計書 F08.8 §4 の {@code GET /api/v1/{scope}/{id}/repair-plan/dashboard} 5 ペイン統合 DTO の
 * {@code upcoming_items[]} 要素に対応する。</p>
 */
@Getter
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RepairPlanUpcomingItemDto {

    @JsonProperty("id")
    private final UUID id;

    @JsonProperty("category")
    private final String category;

    @JsonProperty("title")
    private final String title;

    @JsonProperty("planned_year")
    private final Integer plannedYear;

    @JsonProperty("planned_month")
    private final Integer plannedMonth;

    @JsonProperty("estimated_amount")
    private final Long estimatedAmount;

    @JsonProperty("status")
    private final String status;
}
