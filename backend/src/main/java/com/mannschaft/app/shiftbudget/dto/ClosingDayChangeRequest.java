package com.mannschaft.app.shiftbudget.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.shiftbudget.ShiftBudgetErrorCode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 締め日変更リクエスト（CMP-260909-2152）。
 *
 * <p>{@code closing_day} は整数の 0〜28（0＝末日）。29〜31・-1・null・非数値・小数は 400 SHIFT_BUDGET_037。
 * 型変換の失敗が共通の 400 になってしまわないよう、JSON のまま受けて {@link #requireClosingDay()} で判定する。</p>
 *
 * @param expectedRevision    クライアントが見ている設定の revision（現在値と一致しなければ 409 SHIFT_BUDGET_038）
 * @param closingDay          締め日（0〜28、0＝末日）
 * @param effectivePeriodKey  適用開始の期間キー（YYYY-MM）
 * @param reason              変更理由（必須・500字以内）
 */
public record ClosingDayChangeRequest(
        @JsonProperty("expected_revision")
        @NotNull(message = "expected_revision は必須です")
        Long expectedRevision,

        @JsonProperty("closing_day")
        JsonNode closingDay,

        @JsonProperty("effective_period_key")
        @NotNull(message = "effective_period_key は必須です")
        @Pattern(regexp = "^\\d{4}-(0[1-9]|1[0-2])$", message = "effective_period_key は YYYY-MM 形式で指定してください")
        String effectivePeriodKey,

        @JsonProperty("reason")
        @NotBlank(message = "変更理由は必須です")
        @Size(max = 500, message = "変更理由は500字以内で指定してください")
        String reason
) {

    /** 締め日を整数（0〜28）として取り出す。範囲外・非整数は 400 SHIFT_BUDGET_037。 */
    public int requireClosingDay() {
        if (closingDay == null || closingDay.isNull() || !closingDay.isIntegralNumber() || !closingDay.canConvertToInt()) {
            throw new BusinessException(ShiftBudgetErrorCode.CLOSING_DAY_INVALID);
        }
        int value = closingDay.intValue();
        if (value < 0 || value > 28) {
            throw new BusinessException(ShiftBudgetErrorCode.CLOSING_DAY_INVALID);
        }
        return value;
    }
}
