package com.mannschaft.app.service.dto;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

/**
 * カスタムフィールド定義レスポンス。
 */
@Getter
@Builder
public class FieldResponse {

    private Long id;
    private String fieldName;
    private String fieldType;
    private String description;
    private List<String> options;
    private Boolean isRequired;
    private Integer sortOrder;
    private Boolean isActive;
}
