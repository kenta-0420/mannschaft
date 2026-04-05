package com.mannschaft.app.digest.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * ダイジェスト公開レスポンス。
 */
@Getter
@RequiredArgsConstructor
public class DigestPublishResponse {

    private final Long digestId;
    private final Long blogPostId;
    private final String blogPostSlug;
    private final String status;
    private final StaleWarningResponse staleWarning;
}
