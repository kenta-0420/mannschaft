package com.mannschaft.app.bulletin.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 返信更新リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class UpdateReplyRequest {

    @NotBlank
    private final String body;
}
