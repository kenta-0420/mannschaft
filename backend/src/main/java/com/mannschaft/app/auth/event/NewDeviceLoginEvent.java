package com.mannschaft.app.auth.event;

import com.mannschaft.app.common.event.BaseEvent;
import lombok.Getter;

/**
 * 新規デバイスログイン検知イベント。
 * NewDeviceDetectionService で未知デバイスからのログインを検出した時に発行される。
 */
@Getter
public class NewDeviceLoginEvent extends BaseEvent {

    private final Long userId;
    private final String ipAddress;
    /** 検出されたデバイスのフィンガープリント。 */
    private final String deviceFingerprint;
    /** デバイス名（UserAgent から解析）。 */
    private final String deviceName;
    private final String locale;

    public NewDeviceLoginEvent(Long userId, String ipAddress, String deviceFingerprint, String deviceName,
                               String locale) {
        super();
        this.userId = userId;
        this.ipAddress = ipAddress;
        this.deviceFingerprint = deviceFingerprint;
        this.deviceName = deviceName;
        this.locale = locale;
    }
}
