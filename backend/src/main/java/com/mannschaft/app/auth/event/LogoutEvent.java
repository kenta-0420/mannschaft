package com.mannschaft.app.auth.event;

import com.mannschaft.app.common.event.BaseEvent;
import lombok.Getter;

/**
 * ログアウトイベント。
 */
@Getter
public class LogoutEvent extends BaseEvent {

    /**
     * ログアウトの種別。
     */
    public enum LogoutType {
        /** 単一セッションのログアウト */
        SESSION,
        /** 全セッションのログアウト */
        ALL_SESSIONS
    }

    private final Long userId;
    private final int deviceCount;
    private final LogoutType logoutType;
    /** SESSION logout 時のセッションID（LOGOUT_SESSION eventのmetadataに使用）。ALL_SESSIONS の場合は null */
    private final Long sessionId;

    public LogoutEvent(Long userId, int deviceCount, LogoutType logoutType) {
        super();
        this.userId = userId;
        this.deviceCount = deviceCount;
        this.logoutType = logoutType;
        this.sessionId = null;
    }

    public LogoutEvent(Long userId, int deviceCount, LogoutType logoutType, Long sessionId) {
        super();
        this.userId = userId;
        this.deviceCount = deviceCount;
        this.logoutType = logoutType;
        this.sessionId = sessionId;
    }
}
