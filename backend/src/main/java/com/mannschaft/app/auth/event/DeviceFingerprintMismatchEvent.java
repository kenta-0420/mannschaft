package com.mannschaft.app.auth.event;

import com.mannschaft.app.common.event.BaseEvent;
import lombok.Getter;

/**
 * デバイスフィンガープリント不一致イベント。
 * refreshAccessToken() で User-Agent / IP の不一致を検出した時に発行される。
 */
@Getter
public class DeviceFingerprintMismatchEvent extends BaseEvent {

    private final Long userId;
    /** 不一致が検出されたトークンの DB ID。 */
    private final Long tokenId;

    public DeviceFingerprintMismatchEvent(Long userId, Long tokenId) {
        super();
        this.userId = userId;
        this.tokenId = tokenId;
    }
}
