package com.mannschaft.app.corkboard.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * コルクボード詳細レスポンスDTO（カード・セクション含む）。
 */
@Getter
@RequiredArgsConstructor
public class CorkboardDetailResponse {

    private final Long id;
    private final String scopeType;
    private final Long scopeId;
    private final Long ownerId;
    private final String name;
    private final String backgroundStyle;
    private final String editPolicy;
    private final Boolean isDefault;
    private final Long version;
    private final List<CorkboardCardResponse> cards;
    private final List<CorkboardGroupResponse> groups;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;
}
