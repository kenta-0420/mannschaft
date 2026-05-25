package com.mannschaft.app.corkboard.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * コルクボードレスポンスDTO。
 */
@Builder(toBuilder = true)
@Getter
public class CorkboardResponse {

    private final Long id;
    private final BoardScopeDto scope;
    private final Long ownerId;
    private final String name;
    private final BoardSettingsDto settings;
    private final Long version;
    private final BoardAuditDto audit;

    public record BoardScopeDto(String scopeType, Long scopeId) {}

    public record BoardSettingsDto(String backgroundStyle, String editPolicy, Boolean isDefault) {}

    public record BoardAuditDto(LocalDateTime createdAt, LocalDateTime updatedAt) {}
}
