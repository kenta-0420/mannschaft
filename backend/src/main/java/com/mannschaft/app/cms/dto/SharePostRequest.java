package com.mannschaft.app.cms.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * ブログ記事共有リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class SharePostRequest {

    private final Long teamId;
    private final Long organizationId;
}
