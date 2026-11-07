package com.mannschaft.app.auth.event;

import com.mannschaft.app.auth.TwoFactorMethod;
import com.mannschaft.app.common.event.BaseEvent;
import lombok.Getter;

/**
 * 多要素認証（MFA）有効化イベント。
 *
 * <p>CMP-261012-0910: 有効化した方式（{@link #method}）を持つ（AC-25/AC-35）。</p>
 */
@Getter
public class MfaEnabledEvent extends BaseEvent {

    private final Long userId;
    private final TwoFactorMethod method;

    public MfaEnabledEvent(Long userId) {
        this(userId, TwoFactorMethod.TOTP);
    }

    public MfaEnabledEvent(Long userId, TwoFactorMethod method) {
        super();
        this.userId = userId;
        this.method = method;
    }
}
