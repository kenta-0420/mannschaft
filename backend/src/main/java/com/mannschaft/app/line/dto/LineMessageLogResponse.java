package com.mannschaft.app.line.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * LINEメッセージログレスポンス。
 */
@Getter
@RequiredArgsConstructor
public class LineMessageLogResponse {

    private final Long id;
    private final Long lineBotConfigId;
    private final String direction;
    private final String messageType;
    private final String lineUserId;
    private final String contentSummary;
    private final String lineMessageId;
    private final String status;
    private final String errorDetail;
    private final LocalDateTime createdAt;
}
