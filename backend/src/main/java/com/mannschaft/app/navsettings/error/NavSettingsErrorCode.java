package com.mannschaft.app.navsettings.error;

import com.mannschaft.app.common.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum NavSettingsErrorCode implements ErrorCode {
    NAV_SETTINGS_001("NAV_SETTINGS_001", "固定ナビ項目は変更・削除できません", Severity.WARN),
    NAV_SETTINGS_002("NAV_SETTINGS_002", "固定ナビ項目は非表示にできません", Severity.WARN),
    NAV_SETTINGS_003("NAV_SETTINGS_003", "同じキーのナビ項目が既に存在します", Severity.WARN),
    NAV_SETTINGS_004("NAV_SETTINGS_004", "ナビ項目が見つかりません", Severity.WARN);

    private final String code;
    private final String message;
    private final Severity severity;
}
