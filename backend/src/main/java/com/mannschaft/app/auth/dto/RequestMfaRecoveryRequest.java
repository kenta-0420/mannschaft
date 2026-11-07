package com.mannschaft.app.auth.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;

/**
 * 2FA 回復メールの要求（{@code POST /api/v1/auth/2fa/recovery/request}。公開 EP）。
 *
 * <p>CMP-261012-0910 殿裁定: MFA セッショントークンは本文で受けるのを正とする（アクセスログ・Referer への
 * 漏えい防止。AC-18 と同じ方針）。移行期間中は従来のクエリ {@code ?mfaSessionToken=} も受け付けるため、
 * 本文自体は省略可能とし、必須判定はコントローラーで両経路を合わせて行う。</p>
 */
@Getter
public class RequestMfaRecoveryRequest {

    private final String mfaSessionToken;

    @JsonCreator
    public RequestMfaRecoveryRequest(@JsonProperty("mfaSessionToken") String mfaSessionToken) {
        this.mfaSessionToken = mfaSessionToken;
    }
}
