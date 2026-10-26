package com.mannschaft.app.moderation.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * モデレーション設定変更履歴レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class SettingsHistoryResponse {

    private final Long id;
    private final String settingKey;
    private final String oldValue;
    private final String newValue;
    private final Long changedBy;
    private final LocalDateTime changedAt;
}
