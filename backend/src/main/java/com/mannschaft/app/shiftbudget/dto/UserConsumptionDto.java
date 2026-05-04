package com.mannschaft.app.shiftbudget.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonView;
import com.mannschaft.app.shiftbudget.view.BudgetView;
import lombok.Builder;

import java.math.BigDecimal;

/**
 * F08.7 ユーザー別消化額 DTO。
 *
 * <p>設計書 F08.7 (v1.2) §6.2.3 / §9.3 に準拠。</p>
 *
 * <p>{@link BudgetView.BudgetAdmin} で初めて serialize される。
 * Phase 9-δ 第3段で {@code by_user} 集計が有効化され、{@code BUDGET_ADMIN} 保有時のみ実データが返る。</p>
 *
 * @param userId ユーザーID
 * @param amount 消化額（円）
 * @param hours  勤務時間
 */
@Builder
public record UserConsumptionDto(

        @JsonView(BudgetView.BudgetAdmin.class)
        @JsonProperty("user_id")
        Long userId,

        @JsonView(BudgetView.BudgetAdmin.class)
        @JsonProperty("amount")
        BigDecimal amount,

        @JsonView(BudgetView.BudgetAdmin.class)
        @JsonProperty("hours")
        BigDecimal hours
) {
}
