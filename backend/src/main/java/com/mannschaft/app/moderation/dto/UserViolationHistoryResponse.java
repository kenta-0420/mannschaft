package com.mannschaft.app.moderation.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.List;

/**
 * ユーザー違反履歴レスポンスDTO。違反一覧と統計情報を含む。
 */
@Getter
@RequiredArgsConstructor
public class UserViolationHistoryResponse {

    private final Long userId;
    private final long activeWarningCount;
    private final long activeContentDeleteCount;
    private final long totalViolationCount;
    private final boolean isYabai;
    private final List<ViolationResponse> violations;
}
