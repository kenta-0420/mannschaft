package com.mannschaft.app.family.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * お買い物リストレスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class ShoppingListResponse {

    private final Long id;
    private final Long teamId;
    private final String name;
    private final boolean isTemplate;
    private final String status;
    private final Long createdBy;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;
}
