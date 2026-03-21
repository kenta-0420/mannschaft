package com.mannschaft.app.filesharing.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * タグレスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class TagResponse {

    private final Long id;
    private final Long fileId;
    private final String tagName;
    private final Long userId;
    private final LocalDateTime createdAt;
}
