package com.mannschaft.app.search.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * 検索履歴レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class SearchHistoryResponse {

    private final Long id;
    private final String query;
    private final LocalDateTime searchedAt;
}
