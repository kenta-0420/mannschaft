package com.mannschaft.app.cms.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * ブログ記事共有レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class SharePostResponse {

    private final Long shareId;
    private final Long blogPostId;
    private final Long teamId;
    private final Long organizationId;
}
