package com.mannschaft.app.auth.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;

/**
 * 2FA 回復の確認（{@code POST /api/v1/auth/2fa/recovery/confirm}。公開 EP。メールのリンクから開く）。
 *
 * <p>CMP-261012-0910 殿裁定: 回復トークンは本文で受けるのを正とする。移行期間中は従来のクエリ {@code ?token=}
 * も受け付けるため、本文自体は省略可能とし、必須判定はコントローラーで両経路を合わせて行う。</p>
 */
@Getter
public class ConfirmMfaRecoveryRequest {

    private final String token;

    @JsonCreator
    public ConfirmMfaRecoveryRequest(@JsonProperty("token") String token) {
        this.token = token;
    }
}
