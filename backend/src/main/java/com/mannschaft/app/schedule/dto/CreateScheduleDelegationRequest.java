package com.mannschaft.app.schedule.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * スケジュール代理指定リクエスト（F03.10 §4.1 POST /api/v1/schedules/{scheduleId}/delegations）。
 *
 * <p>委任者が代理人を指定する。{@code reason} は機微情報を含みうるため任意入力（§6）。</p>
 */
@Getter
@Setter
@NoArgsConstructor
public class CreateScheduleDelegationRequest {

    /** 代理人 user_id（必須）。 */
    @NotNull(message = "代理人IDは必須です")
    private Long delegateId;

    /** 委任理由（任意・最大500文字）。 */
    @Size(max = 500, message = "委任理由は500文字以内で入力してください")
    private String reason;
}
