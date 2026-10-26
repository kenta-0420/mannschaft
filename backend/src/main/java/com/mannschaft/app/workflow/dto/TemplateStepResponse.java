package com.mannschaft.app.workflow.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * テンプレートステップレスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class TemplateStepResponse {

    private final Long id;
    private final Long templateId;
    private final Integer stepOrder;
    private final String name;
    private final String approvalType;
    private final String approverType;
    private final String approverUserIds;
    private final String approverRole;
    private final Short autoApproveDays;
}
