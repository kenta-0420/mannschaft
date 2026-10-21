package com.mannschaft.app.auth.event;

import com.mannschaft.app.auth.MfaEmailOtpPurpose;
import com.mannschaft.app.common.event.BaseEvent;
import lombok.Getter;

/**
 * メールOTP 送信要求イベント（CMP-261012-0910 AC-19/AC-26）。
 *
 * <p>{@code MFA_EMAIL_OTP} の email_outbox enqueue をトリガーする。平文コードを運ぶため
 * {@code toString} やログへ出力してはならない（AC-26）。{@link #sourceEventId} は送信ごとに一意。</p>
 */
@Getter
public class MfaEmailOtpRequestedEvent extends BaseEvent {

    private final Long userId;
    private final String email;
    private final String code;
    private final MfaEmailOtpPurpose purpose;
    private final long expiresInSeconds;
    /** email_outbox の冪等キーに使う送信ごと一意の ID。 */
    private final String sourceEventId;

    public MfaEmailOtpRequestedEvent(Long userId, String email, String code, MfaEmailOtpPurpose purpose,
                                     long expiresInSeconds, String sourceEventId) {
        super();
        this.userId = userId;
        this.email = email;
        this.code = code;
        this.purpose = purpose;
        this.expiresInSeconds = expiresInSeconds;
        this.sourceEventId = sourceEventId;
    }

    @Override
    public String toString() {
        return "MfaEmailOtpRequestedEvent{userId=" + userId + ", purpose=" + purpose
                + ", sourceEventId=" + sourceEventId + "}";
    }
}
