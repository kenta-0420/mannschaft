package com.mannschaft.app.cms.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * ブログ記事リビジョンレスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class RevisionResponse {

    private final Long id;
    private final Integer revisionNumber;
    private final String title;
    private final Long editorId;
    private final String changeSummary;
    private final LocalDateTime createdAt;
}
