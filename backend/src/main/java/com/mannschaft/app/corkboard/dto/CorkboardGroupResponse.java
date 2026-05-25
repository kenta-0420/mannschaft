package com.mannschaft.app.corkboard.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * コルクボードセクションレスポンスDTO。
 */
@Builder(toBuilder = true)
@Getter
public class CorkboardGroupResponse {

    private final Long id;
    private final Long corkboardId;
    private final String name;
    private final Boolean isCollapsed;
    private final GroupLayoutDto layout;
    private final Short displayOrder;
    private final GroupAuditDto audit;

    public record GroupLayoutDto(Integer positionX, Integer positionY, Integer width, Integer height) {}

    public record GroupAuditDto(LocalDateTime createdAt, LocalDateTime updatedAt) {}
}
