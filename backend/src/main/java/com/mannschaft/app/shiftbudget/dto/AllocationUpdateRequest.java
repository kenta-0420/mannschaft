package com.mannschaft.app.shiftbudget.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * F08.7 シフト予算割当 更新リクエスト DTO。
 *
 * <p>設計書 F08.7 (v1.2) §6.2.1 / §9.2 楽観的ロックに準拠。</p>
 *
 * <p>更新可能フィールドは {@code allocated_amount} と {@code note} のみ。
 * 期間・スコープ・費目の変更は不可（変更したい場合は新規割当を作成する運用）。</p>
 *
 * @param allocatedAmount 割当額（円, 0 以上）
 * @param note            備考
 * @param version         楽観ロック用バージョン番号（取得時の値を返す）
 */
public record AllocationUpdateRequest(

        @NotNull
        @PositiveOrZero
        @JsonProperty("allocated_amount")
        BigDecimal allocatedAmount,

        @Size(max = 500)
        @JsonProperty("note")
        String note,

        @NotNull
        @JsonProperty("version")
        Long version
) {
}
