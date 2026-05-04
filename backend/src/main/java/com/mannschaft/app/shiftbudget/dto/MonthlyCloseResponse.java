package com.mannschaft.app.shiftbudget.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;

/**
 * F08.7 シフト予算 月次締め レスポンス DTO（API #11）。
 *
 * <p>設計書 F08.7 (v1.2) §6.1 #11 / §4.6 に準拠。</p>
 */
@Builder
public record MonthlyCloseResponse(

        @JsonProperty("organization_id")
        Long organizationId,

        @JsonProperty("year_month")
        String yearMonth,

        /** 今回新規に締めた allocation 件数 */
        @JsonProperty("closed_allocations")
        int closedAllocations,

        /** 既に締め済として skip した allocation 件数（冪等性確認用） */
        @JsonProperty("already_closed_allocations")
        int alreadyClosedAllocations,

        /** PLANNED → CONFIRMED に遷移した consumption レコードの総件数 */
        @JsonProperty("closed_consumptions")
        int closedConsumptions
) {
}
