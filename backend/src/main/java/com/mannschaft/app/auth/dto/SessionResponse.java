package com.mannschaft.app.auth.dto;

import java.time.LocalDateTime;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * アクティブセッション情報のレスポンス（F12.4）。
 */
@Getter
@RequiredArgsConstructor
public class SessionResponse {

    private final Long id;
    /** パース済みデバイス名（手動設定 > UA自動生成 > "Unknown Device"） */
    private final String deviceName;
    /** デバイス種別（DESKTOP / MOBILE / TABLET / UNKNOWN） */
    private final String deviceType;
    private final String ipAddress;
    private final String userAgent;
    private final boolean rememberMe;
    private final LocalDateTime createdAt;
    /** null許容: 新規発行直後のトークンは null */
    private final LocalDateTime lastUsedAt;
    /** セッション有効期限 */
    private final LocalDateTime expiresAt;
    private final boolean isCurrent;
}
