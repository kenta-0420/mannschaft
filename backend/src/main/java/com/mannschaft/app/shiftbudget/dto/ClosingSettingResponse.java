package com.mannschaft.app.shiftbudget.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.mannschaft.app.shiftbudget.service.ShiftBudgetClosingSettingService;

import java.time.Instant;
import java.util.List;

/**
 * 締め日設定の応答（CMP-260909-2152）。FE 試練と共有する契約。
 *
 * @param closingDay            今日を含む期間の締め日（0＝末日）
 * @param revision              現在の revision（設定なしは0）
 * @param minEffectivePeriodKey 締め日変更の適用開始の下限（今日を含む期間キーと、確定済み期間の最大キーの次、の遅い方）
 * @param changes               変更履歴（revision 昇順）
 */
public record ClosingSettingResponse(
        @JsonProperty("closing_day") int closingDay,
        @JsonProperty("revision") int revision,
        @JsonProperty("min_effective_period_key") String minEffectivePeriodKey,
        @JsonProperty("changes") List<Change> changes
) {

    /** 変更履歴の1件。 */
    public record Change(
            @JsonProperty("revision") int revision,
            @JsonProperty("effective_period_key") String effectivePeriodKey,
            @JsonProperty("closing_day") int closingDay,
            @JsonProperty("previous_closing_day") int previousClosingDay,
            @JsonProperty("reason") String reason,
            @JsonProperty("actor_user_id") Long actorUserId,
            @JsonProperty("created_at") Instant createdAt
    ) {
    }

    public static ClosingSettingResponse from(ShiftBudgetClosingSettingService.ClosingSettingView view) {
        return new ClosingSettingResponse(view.closingDay(), view.revision(), view.minEffectivePeriodKey(),
                view.changes().stream()
                        .map(c -> new Change(c.revision(), c.effectivePeriodKey(), c.closingDay(), c.previousClosingDay(),
                                c.reason(), c.actorUserId(), c.createdAt()))
                        .toList());
    }
}
