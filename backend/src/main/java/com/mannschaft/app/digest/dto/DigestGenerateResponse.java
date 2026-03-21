package com.mannschaft.app.digest.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * ダイジェスト生成レスポンス。
 */
@Getter
@RequiredArgsConstructor
public class DigestGenerateResponse {

    private final Long id;
    private final String status;
    private final Integer estimatedPostCount;
}
