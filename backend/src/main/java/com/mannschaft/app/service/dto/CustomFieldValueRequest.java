package com.mannschaft.app.service.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

/**
 * カスタムフィールド値リクエスト。
 */
@Getter
@Setter
public class CustomFieldValueRequest {

    @NotNull
    private Long fieldId;

    private String value;
}
