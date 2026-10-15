package com.mannschaft.app.family.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * お買い物アイテムレスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class ShoppingItemResponse {

    private final Long id;
    private final Long listId;
    private final String name;
    private final String quantity;
    private final String note;
    private final Long assignedTo;
    private final boolean isChecked;
    private final Long checkedBy;
    private final LocalDateTime checkedAt;
    private final int sortOrder;
    private final Long createdBy;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;
}
