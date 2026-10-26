package com.mannschaft.app.directmail.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * メールプレビューリクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class PreviewMailRequest {

    @NotBlank
    private final String bodyMarkdown;
}
