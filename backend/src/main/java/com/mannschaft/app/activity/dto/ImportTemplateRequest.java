package com.mannschaft.app.activity.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * テンプレートインポートリクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class ImportTemplateRequest {

    private final Long teamId;
    private final Long organizationId;
    private final Long sourceTemplateId;
    private final String shareCode;
}
