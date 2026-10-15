package com.mannschaft.app.filesharing.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * 共有リンクレスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class LinkResponse {

    private final Long id;
    private final Long fileId;
    private final String token;
    private final LocalDateTime expiresAt;
    private final boolean hasPassword;
    private final Integer accessCount;
    private final LocalDateTime lastAccessedAt;
    private final Long createdBy;
    private final LocalDateTime createdAt;
    /** PR-D: リンクが有効（手動失効していない）か。 */
    private final boolean active;
    /** PR-D: このリンクでダウンロードを許可するか（既定 false=閲覧のみ）。 */
    private final boolean downloadAllowed;
}
