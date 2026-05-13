package com.mannschaft.app.template.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * 組織有効モジュールレスポンス。
 * TeamModuleResponse と対称的なフィールド構成で組織スコープのモジュール状態を返す。
 */
@Getter
@RequiredArgsConstructor
public class OrgModuleResponse {

    private final Long moduleId;
    private final String moduleName;
    private final String moduleSlug;
    private final Boolean isEnabled;
    private final LocalDateTime enabledAt;
}
