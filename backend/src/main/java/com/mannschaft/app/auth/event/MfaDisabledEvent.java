package com.mannschaft.app.auth.event;

import com.mannschaft.app.auth.TwoFactorMethod;
import com.mannschaft.app.common.event.BaseEvent;
import lombok.Getter;

/**
 * 多要素認証（MFA）無効化イベント。
 *
 * <p>CMP-261012-0910: 無効化した方式（{@link #method}）と理由（{@link #reason}: {@code USER} /
 * {@code RECOVERY}）を持つ（AC-31/AC-35）。旧コンストラクタは理由・方式不明（null）として扱う。</p>
 */
@Getter
public class MfaDisabledEvent extends BaseEvent {

    /** 本人がログイン中に無効化した。 */
    public static final String REASON_USER = "USER";
    /** 回復メール経由で無効化した。 */
    public static final String REASON_RECOVERY = "RECOVERY";

    private final Long userId;
    private final TwoFactorMethod method;
    private final String reason;

    public MfaDisabledEvent(Long userId) {
        this(userId, null, null);
    }

    public MfaDisabledEvent(Long userId, TwoFactorMethod method, String reason) {
        super();
        this.userId = userId;
        this.method = method;
        this.reason = reason;
    }
}
