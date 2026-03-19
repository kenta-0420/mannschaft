package com.mannschaft.app.template.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * チーム有効モジュールレスポンス。
 */
@Getter
@RequiredArgsConstructor
public class TeamModuleResponse {

    private final Long moduleId;
    private final String moduleName;
    private final String moduleSlug;
    private final Boolean isEnabled;
    private final LocalDateTime enabledAt;
    private final LocalDateTime trialExpiresAt;
}
