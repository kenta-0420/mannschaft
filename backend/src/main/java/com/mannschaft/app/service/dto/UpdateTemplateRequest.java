package com.mannschaft.app.service.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * テンプレート更新リクエスト。
 */
@Getter
@Setter
public class UpdateTemplateRequest {

    @NotBlank
    @Size(max = 100)
    private String name;

    @Size(max = 200)
    private String titleTemplate;

    private String noteTemplate;

    private Integer defaultDurationMinutes;

    private Integer sortOrder;

    private List<TemplateFieldValueRequest> customFieldValues;
}
