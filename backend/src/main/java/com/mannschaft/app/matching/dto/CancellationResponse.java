package com.mannschaft.app.matching.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * キャンセル履歴レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class CancellationResponse {

    private final Long proposalId;
    private final String requestTitle;
    private final String cancellationType;
    private final String statusReason;
    private final LocalDateTime cancelledAt;
}
