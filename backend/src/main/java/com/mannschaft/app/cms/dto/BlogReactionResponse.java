package com.mannschaft.app.cms.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * ブログ記事リアクション（みたよ！）レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class BlogReactionResponse {

    private final Long blogPostId;
    private final boolean mitayo;
    private final int mitayoCount;
}
