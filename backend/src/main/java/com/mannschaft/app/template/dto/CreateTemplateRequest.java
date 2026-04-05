package com.mannschaft.app.template.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.List;

/**
 * テンプレート作成リクエスト。
 */
@Getter
@RequiredArgsConstructor
public class CreateTemplateRequest {

    @NotBlank
    private final String name;

    @NotBlank
    private final String slug;

    private final String description;

    private final String category;

    private final List<Long> moduleIds;
}
