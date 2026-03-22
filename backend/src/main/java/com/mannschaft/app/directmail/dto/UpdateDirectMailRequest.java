package com.mannschaft.app.directmail.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * ダイレクトメール編集リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class UpdateDirectMailRequest {

    @NotBlank
    @Size(max = 200)
    private final String subject;

    @NotBlank
    private final String bodyMarkdown;

    @NotBlank
    private final String bodyHtml;

    @NotBlank
    @Size(max = 20)
    private final String recipientType;

    private final String recipientFilter;

    private final Integer estimatedRecipients;
}
