package com.mannschaft.app.directmail.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * ダイレクトメールテンプレート更新リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class UpdateDirectMailTemplateRequest {

    @NotBlank
    @Size(max = 100)
    private final String name;

    @NotBlank
    @Size(max = 200)
    private final String subject;

    @NotBlank
    private final String bodyMarkdown;
}
