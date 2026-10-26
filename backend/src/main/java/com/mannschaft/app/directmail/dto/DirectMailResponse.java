package com.mannschaft.app.directmail.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * ダイレクトメールレスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class DirectMailResponse {

    private final Long id;
    private final String scopeType;
    private final Long scopeId;
    private final Long senderId;
    private final String subject;
    private final String bodyMarkdown;
    private final String bodyHtml;
    private final String recipientType;
    private final String recipientFilter;
    private final Integer estimatedRecipients;
    private final Integer totalRecipients;
    private final Integer sentCount;
    private final Integer openedCount;
    private final Integer bouncedCount;
    private final String status;
    private final LocalDateTime scheduledAt;
    private final String errorMessage;
    private final LocalDateTime sentAt;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;
}
