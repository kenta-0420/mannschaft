package com.mannschaft.app.moderation.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * 通報内部メモレスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class InternalNoteResponse {

    private final Long id;
    private final Long reportId;
    private final Long authorId;
    private final String note;
    private final LocalDateTime createdAt;
}
