package com.mannschaft.app.timeline.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * タイムラインリアクション（みたよ！）レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class ReactionResponse {

    private final Long timelinePostId;
    private final boolean mitayo;
    private final int mitayoCount;
}
