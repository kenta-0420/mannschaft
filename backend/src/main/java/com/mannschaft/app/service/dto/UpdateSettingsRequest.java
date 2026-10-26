package com.mannschaft.app.service.dto;

import lombok.Getter;
import lombok.Setter;

/**
 * 機能設定更新リクエスト。
 */
@Getter
@Setter
public class UpdateSettingsRequest {

    private Boolean isDashboardEnabled;

    private Boolean isReactionEnabled;
}
