package com.mannschaft.app.shiftbudget.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * F08.7 TODO/プロジェクト 予算紐付 作成リクエスト DTO（Phase 9-γ）。
 *
 * <p>設計書 F08.7 (v1.2) §6.2.4 に準拠。</p>
 *
 * <p><strong>排他ルール（Service 層で検証）</strong>:</p>
 * <ul>
 *   <li>{@code projectId} と {@code todoId} はどちらか一方のみ NOT NULL</li>
 *   <li>{@code linkAmount} と {@code linkPercentage} は排他（両方 NULL = 割当全額）</li>
 * </ul>
 *
 * @param projectId       紐付対象プロジェクトID（{@code todoId} と排他）
 * @param todoId          紐付対象 TODO ID（{@code projectId} と排他）
 * @param allocationId    紐付先割当ID
 * @param linkAmount      紐付上限金額（円, 0 以上）
 * @param linkPercentage  割合（0.00〜100.00）
 * @param currency        通貨コード（NULL 時は JPY）
 */
public record TodoBudgetLinkCreateRequest(

        @JsonProperty("project_id")
        Long projectId,

        @JsonProperty("todo_id")
        Long todoId,

        @NotNull
        @JsonProperty("allocation_id")
        Long allocationId,

        @PositiveOrZero
        @JsonProperty("link_amount")
        BigDecimal linkAmount,

        @DecimalMin("0.00")
        @DecimalMax("100.00")
        @JsonProperty("link_percentage")
        BigDecimal linkPercentage,

        @Size(min = 3, max = 3)
        @JsonProperty("currency")
        String currency
) {
}
