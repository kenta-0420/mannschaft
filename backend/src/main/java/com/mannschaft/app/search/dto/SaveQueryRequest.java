package com.mannschaft.app.search.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 検索クエリ保存リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class SaveQueryRequest {

    @NotBlank
    @Size(max = 50)
    private final String name;

    @NotNull
    private final String queryParams;
}
