package com.mannschaft.app.bulletin.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * 既読ステータスレスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class ReadStatusResponse {

    private final Long id;
    private final Long threadId;
    private final Long userId;
    private final LocalDateTime readAt;
}
