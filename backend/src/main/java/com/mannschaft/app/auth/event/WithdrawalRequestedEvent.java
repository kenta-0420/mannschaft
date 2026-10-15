package com.mannschaft.app.auth.event;

import com.mannschaft.app.common.event.BaseEvent;
import lombok.Getter;

/**
 * 退会申請イベント。猶予期間開始と関連リソースの処理をトリガーする。
 */
@Getter
public class WithdrawalRequestedEvent extends BaseEvent {

    private final Long userId;
    private final String email;

    public WithdrawalRequestedEvent(Long userId, String email) {
        super();
        this.userId = userId;
        this.email = email;
    }
}
