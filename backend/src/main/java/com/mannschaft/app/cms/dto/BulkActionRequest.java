package com.mannschaft.app.cms.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.List;

/**
 * 一括操作リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class BulkActionRequest {

    @NotEmpty
    private final List<Long> ids;

    @NotBlank
    private final String action;
}
