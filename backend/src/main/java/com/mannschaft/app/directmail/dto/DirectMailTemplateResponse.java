package com.mannschaft.app.directmail.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * ダイレクトメールテンプレートレスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class DirectMailTemplateResponse {

    private final Long id;
    private final String scopeType;
    private final Long scopeId;
    private final String name;
    private final String subject;
    private final String bodyMarkdown;
    private final Long createdBy;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;
}
