package com.mannschaft.app.notification.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * 通知種別設定レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class TypePreferenceResponse {

    private final Long id;
    private final Long userId;
    private final String notificationType;
    private final Boolean isEnabled;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;
}
