package com.mannschaft.app.shiftbudget.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.mannschaft.app.shiftbudget.service.ShiftBudgetClosePeriodResolver;

import java.time.LocalDate;
import java.util.List;

/**
 * 締め期間一覧の応答（CMP-260909-2152）。FE 試練と共有する契約: {@code data.items:[{period_key, period_start, period_end,
 * closing_day, ended}]}。
 *
 * <p>{@code ended} はサーバーの業務日（{@code ShiftBudgetBusinessDateProvider}、Asia/Tokyo）で判定した終了済みフラグ
 * （終了日当日を含む）。締め可否を利用者端末のタイムゾーンで判定させない（再検分 P2-4）。</p>
 */
public record ClosePeriodListResponse(@JsonProperty("items") List<ClosePeriodItem> items) {

    /** 締め期間の1件。 */
    public record ClosePeriodItem(
            @JsonProperty("period_key") String periodKey,
            @JsonProperty("period_start") LocalDate periodStart,
            @JsonProperty("period_end") LocalDate periodEnd,
            @JsonProperty("closing_day") int closingDay,
            @JsonProperty("ended") boolean ended
    ) {
    }

    /**
     * @param businessDate サーバーの業務日。期間終了日がこの日以前なら終了済み（締め API の 036 判定と同じ基準）
     */
    public static ClosePeriodListResponse from(List<ShiftBudgetClosePeriodResolver.ResolvedPeriod> periods,
                                               LocalDate businessDate) {
        return new ClosePeriodListResponse(periods.stream()
                .map(p -> new ClosePeriodItem(p.periodKey().toString(), p.start(), p.end(), p.closingDay(),
                        !p.end().isAfter(businessDate)))
                .toList());
    }
}
