package com.mannschaft.app.corkboard.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * コルクボードセクション更新リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class UpdateGroupRequest {

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
