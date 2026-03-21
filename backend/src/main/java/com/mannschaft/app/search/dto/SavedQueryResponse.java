package com.mannschaft.app.search.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * 保存済み検索クエリレスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class SavedQueryResponse {

    private final Long id;
    private final String name;
    private final String queryParams;
    private final LocalDateTime createdAt;
}
