package com.mannschaft.app.supporter.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * サポーター設定更新リクエスト。
 */
@Getter
@NoArgsConstructor
public class UpdateSupporterSettingsRequest {

    /** 自動承認: true=申請を即時承認、false=管理者手動承認 */
    private boolean autoApprove;
}
