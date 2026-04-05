package com.mannschaft.app.dashboard.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * スコープカバレッジ情報レスポンス。
 */
@Getter
@RequiredArgsConstructor
public class ScopeCoverageResponse {

    private final int totalScopes;
    private final int displayedScopes;
    private final boolean hasHiddenScopes;
}
