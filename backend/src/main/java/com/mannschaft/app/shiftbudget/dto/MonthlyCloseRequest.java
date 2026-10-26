package com.mannschaft.app.shiftbudget.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * F08.7 シフト予算 月次締め リクエスト DTO（API #11）。
 *
 * <p>設計書 F08.7 (v1.2) §6.1 #11 / §4.6 に準拠。</p>
 *
 * <p>{@code yearMonth} は ISO 8601 部分形式 ({@code "YYYY-MM"}) で受け取る。
 * 正規表現バリデーションで形式違反は 400 を返す（{@code java.time.YearMonth#parse} 例外で
 * 500 にならないよう事前に弾く）。</p>
 */
public record MonthlyCloseRequest(

        @JsonProperty("organization_id")
        @NotNull(message = "organization_id は必須です")
        Long organizationId,

        @JsonProperty("year_month")
        @NotNull(message = "year_month は必須です")
        @Pattern(regexp = "^\\d{4}-(0[1-9]|1[0-2])$",
                message = "year_month は YYYY-MM 形式で指定してください")
        String yearMonth
) {
}
