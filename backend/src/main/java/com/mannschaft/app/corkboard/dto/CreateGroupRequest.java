package com.mannschaft.app.corkboard.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * コルクボードセクション作成リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class CreateGroupRequest {

    @NotBlank
    @Size(max = 100)
    private final String name;

    private final Boolean isCollapsed;

    private final Integer positionX;

    private final Integer positionY;

    private final Integer width;

    private final Integer height;

    private final Short displayOrder;
}
