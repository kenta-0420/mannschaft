package com.mannschaft.app.auth.event;

import com.mannschaft.app.auth.MfaVerificationMethod;
import com.mannschaft.app.common.event.BaseEvent;
import lombok.Getter;

/**
 * 2FA ログイン検証の失敗イベント（CMP-261012-0910 AC-49）。
 *
 * <p>監査 {@code MFA_FAILED} を記録する。失敗応答は例外送出で業務 TX がロールバックされるため、
 * {@code AFTER_COMMIT} の通常リスナーでは届かない。記録経路は失敗 TX から独立させること
 * （出陣で実装。型のみ骨格で確定）。</p>
 */
@Getter
public class MfaVerificationFailedEvent extends BaseEvent {

    private final Long userId;
    private final MfaVerificationMethod method;
    private final String ipAddress;
    private final String userAgent;
    /** 同一 MFA セッションでの通算失敗回数（照合前 INCR 後の値）。 */
    private final long attemptCount;

    public MfaVerificationFailedEvent(Long userId, MfaVerificationMethod method,
                                      String ipAddress, String userAgent, long attemptCount) {
        super();
        this.userId = userId;
        this.method = method;
        this.ipAddress = ipAddress;
        this.userAgent = userAgent;
        this.attemptCount = attemptCount;
    }
}
