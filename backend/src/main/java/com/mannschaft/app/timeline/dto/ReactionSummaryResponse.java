package com.mannschaft.app.timeline.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * タイムラインリアクション集計レスポンスDTO。絵文字ごとのリアクション数を返す。
 */
@Getter
@RequiredArgsConstructor
public class ReactionSummaryResponse {

    private final String emoji;
    private final Long count;
}
