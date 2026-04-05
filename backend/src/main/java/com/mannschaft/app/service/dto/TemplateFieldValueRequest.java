package com.mannschaft.app.service.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

/**
 * テンプレートカスタムフィールド値リクエスト。
 */
@Getter
@Setter
public class TemplateFieldValueRequest {

    @NotNull
    private Long fieldId;

    private String defaultValue;
}
