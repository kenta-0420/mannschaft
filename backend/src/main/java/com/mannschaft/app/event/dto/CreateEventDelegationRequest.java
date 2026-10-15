package com.mannschaft.app.event.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * イベント代理指定リクエスト（F03.10 §4.2 POST /api/v1/events/{eventId}/delegations）。
 *
 * <p>委任者が代理人を指定する。{@code proxyVoteSessionId} を指定すると F08.3 投票代理と
 * 任意連携する（§5.5）。{@code reason} は機微情報を含みうるため任意入力（§6）。</p>
 */
@Getter
@Setter
@NoArgsConstructor
public class CreateEventDelegationRequest {

    /** 代理人 user_id（必須）。 */
    @NotNull(message = "代理人IDは必須です")
    private Long delegateId;

    /** 委任理由（任意・最大500文字）。 */
    @Size(max = 500, message = "委任理由は500文字以内で入力してください")
    private String reason;

    /** F08.3 投票代理と連携する投票セッション ID（任意）。 */
    private Long proxyVoteSessionId;
}
