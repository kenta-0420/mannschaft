package com.mannschaft.app.circulation.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 回覧文書統計レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class DocumentStatsResponse {

    private final long total;
    private final long draft;
    private final long active;
    private final long completed;
    private final long cancelled;
}
