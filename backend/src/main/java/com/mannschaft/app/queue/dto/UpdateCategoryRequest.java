package com.mannschaft.app.queue.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * カテゴリ更新リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class UpdateCategoryRequest {

    @NotBlank
    @Size(max = 50)
    private final String name;

    private final String queueMode;

    @Size(max = 5)
    private final String prefixChar;

    private final Short maxQueueSize;

    private final Short displayOrder;
}
