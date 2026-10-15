package com.mannschaft.app.service.dto;

import lombok.Builder;
import lombok.Getter;

/**
 * 機能設定レスポンス。
 */
@Getter
@Builder
public class SettingsResponse {

    private Long teamId;
    private Boolean isDashboardEnabled;
    private Boolean isReactionEnabled;
}
