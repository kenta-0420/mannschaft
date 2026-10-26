package com.mannschaft.app.auth.event;

import com.mannschaft.app.common.event.BaseEvent;
import lombok.Getter;

/**
 * ユーザー退会（即時匿名化）完了イベント。
 * 個人情報の消去が完了したタイミングで発行される。
 * メールアドレスは匿名化前の値を保持し、通知・監査ログ用途に使用する。
 */
@Getter
public class UserAnonymizedEvent extends BaseEvent {

    private final Long userId;
    /** 匿名化前のメールアドレス（通知・監査ログ用）。 */
    private final String originalEmail;

    public UserAnonymizedEvent(Long userId, String originalEmail) {
        super();
        this.userId = userId;
        this.originalEmail = originalEmail;
    }
}
