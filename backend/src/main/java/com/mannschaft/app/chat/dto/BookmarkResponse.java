package com.mannschaft.app.chat.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * ブックマークレスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class BookmarkResponse {

    private final Long id;
    private final Long messageId;
    private final Long userId;
    private final String note;
    private final LocalDateTime createdAt;
}
