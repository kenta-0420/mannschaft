package com.mannschaft.app.directmail.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * ダイレクトメール送信統計レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class DirectMailStatsResponse {

    private final Long mailLogId;
    private final Integer totalRecipients;
    private final Integer sentCount;
    private final Integer openedCount;
    private final Integer bouncedCount;
    private final Double openRate;
    private final Double bounceRate;
}
