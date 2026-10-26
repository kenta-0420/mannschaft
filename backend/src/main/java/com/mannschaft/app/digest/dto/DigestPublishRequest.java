package com.mannschaft.app.digest.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.List;

/**
 * ダイジェスト公開（ブログ下書き保存）リクエスト。
 */
@Getter
@RequiredArgsConstructor
public class DigestPublishRequest {

    private final String title;

    private final String body;

    private final List<Long> tagIds;

    private final String visibility;
}
