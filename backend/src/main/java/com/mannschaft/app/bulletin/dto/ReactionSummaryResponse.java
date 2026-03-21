package com.mannschaft.app.bulletin.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * リアクション集計レスポンスDTO。絵文字別のカウントを返す。
 */
@Getter
@RequiredArgsConstructor
public class ReactionSummaryResponse {

    private final String emoji;
    private final Long count;
}
