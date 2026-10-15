package com.mannschaft.app.moderation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 異議申立て理由送信リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class SubmitAppealRequest {

    @NotBlank
    @Size(max = 5000)
    private final String appealReason;

    @NotBlank
    private final String token;
}
