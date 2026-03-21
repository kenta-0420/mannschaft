package com.mannschaft.app.digest.dto;

import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * ダイジェストインライン編集リクエスト。
 */
@Getter
@RequiredArgsConstructor
public class DigestEditRequest {

    @Size(max = 200)
    private final String generatedTitle;

    private final String generatedBody;

    @Size(max = 500)
    private final String generatedExcerpt;
}
