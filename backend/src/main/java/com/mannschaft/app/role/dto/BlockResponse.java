package com.mannschaft.app.role.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * ブロックレスポンス。
 */
@Getter
@RequiredArgsConstructor
public class BlockResponse {

    private final Long id;
    private final Long userId;
    private final String displayName;
    private final String blockedByName;
    private final String reason;
    private final LocalDateTime createdAt;
}
