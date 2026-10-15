package com.mannschaft.app.filesharing.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * スターレスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class StarResponse {

    private final Long id;
    private final Long fileId;
    private final Long userId;
    private final LocalDateTime createdAt;
}
