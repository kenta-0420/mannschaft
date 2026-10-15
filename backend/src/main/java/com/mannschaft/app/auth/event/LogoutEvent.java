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
        ALL_SESSIONS,
        /** パスワード変更に伴う他セッション一括ログアウト（F12.4 §5.6） */
        PASSWORD_CHANGE
    }

    private final Long userId;
    private final int deviceCount;
    private final LogoutType logoutType;
    /** SESSION logout 時のセッションID（LOGOUT_SESSION eventのmetadataに使用）。ALL_SESSIONS の場合は null */
    private final Long sessionId;
    /** SHA-256(refresh_token_jti)。監査ログの session_hash に記録する。SESSION logout 時のみ設定。 */
    private final String sessionHash;

    public LogoutEvent(Long userId, int deviceCount, LogoutType logoutType) {
        super();
        this.userId = userId;
        this.deviceCount = deviceCount;
        this.logoutType = logoutType;
        this.sessionId = null;
        this.sessionHash = null;
    }

    public LogoutEvent(Long userId, int deviceCount, LogoutType logoutType, Long sessionId) {
        super();
        this.userId = userId;
        this.deviceCount = deviceCount;
        this.logoutType = logoutType;
        this.sessionId = sessionId;
        this.sessionHash = null;
    }

    public LogoutEvent(Long userId, int deviceCount, LogoutType logoutType, Long sessionId, String sessionHash) {
        super();
        this.userId = userId;
        this.deviceCount = deviceCount;
        this.logoutType = logoutType;
        this.sessionId = sessionId;
        this.sessionHash = sessionHash;
    }
}
