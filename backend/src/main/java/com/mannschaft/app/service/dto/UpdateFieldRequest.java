package com.mannschaft.app.service.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * カスタムフィールド更新リクエスト。
 */
@Getter
@Setter
public class UpdateFieldRequest {

    @NotBlank
    @Size(max = 100)
    private String fieldName;

    @NotNull
    private String fieldType;

    @Size(max = 500)
    private String description;

    private List<String> options;

    private Boolean isRequired;

    private Integer sortOrder;

    private Boolean isActive;
}
