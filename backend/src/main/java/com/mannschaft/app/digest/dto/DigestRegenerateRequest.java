package com.mannschaft.app.digest.dto;

import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * ダイジェスト再生成リクエスト。
 */
@Getter
@RequiredArgsConstructor
public class DigestRegenerateRequest {

    private final String digestStyle;

    @Size(max = 500)
    private final String customPromptSuffix;
}
