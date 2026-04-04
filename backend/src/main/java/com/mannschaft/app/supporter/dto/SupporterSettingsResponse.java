package com.mannschaft.app.supporter.dto;

/**
 * サポーター設定レスポンス。
 *
 * @param autoApprove 自動承認: true=申請を即時承認、false=管理者手動承認
 */
public record SupporterSettingsResponse(boolean autoApprove) {
}
