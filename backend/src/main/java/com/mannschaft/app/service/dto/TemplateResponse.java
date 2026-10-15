package com.mannschaft.app.service.dto;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

/**
 * テンプレートレスポンス。
 */
@Getter
@Builder
public class TemplateResponse {

    private Long id;
    private String name;
    private String titleTemplate;
    private String noteTemplate;
    private Integer defaultDurationMinutes;
    private Integer sortOrder;
    private String scope;
    private Long teamId;
    private Long organizationId;
    private List<TemplateFieldValueResponse> customFieldValues;
}
