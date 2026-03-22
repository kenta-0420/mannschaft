package com.mannschaft.app.corkboard.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * コルクボードレスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class CorkboardResponse {

    private final Long id;
    private final String scopeType;
    private final Long scopeId;
    private final Long ownerId;
    private final String name;
    private final String backgroundStyle;
    private final String editPolicy;
    private final Boolean isDefault;
    private final Long version;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;
}
