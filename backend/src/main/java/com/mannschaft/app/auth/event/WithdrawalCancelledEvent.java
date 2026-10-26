package com.mannschaft.app.auth.event;

import com.mannschaft.app.common.event.BaseEvent;
import lombok.Getter;

/**
 * 退会キャンセルイベント。ユーザーが退会申請をキャンセルした際に発行される。
 */
@Getter
public class WithdrawalCancelledEvent extends BaseEvent {

    private final Long userId;

    public WithdrawalCancelledEvent(Long userId) {
        super();
        this.userId = userId;
    }
}
