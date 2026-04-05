package com.mannschaft.app.digest.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * ダイジェスト生成トリガーユーザー情報。
 */
@Getter
@RequiredArgsConstructor
public class DigestTriggeredByResponse {

    private final Long id;
    private final String displayName;
}
