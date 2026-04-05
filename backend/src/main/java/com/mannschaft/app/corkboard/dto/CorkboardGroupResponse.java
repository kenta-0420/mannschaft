package com.mannschaft.app.corkboard.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * コルクボードセクションレスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class CorkboardGroupResponse {

    private final Long id;
    private final Long corkboardId;
    private final String name;
    private final Boolean isCollapsed;
    private final Integer positionX;
    private final Integer positionY;
    private final Integer width;
    private final Integer height;
    private final Short displayOrder;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;
}
