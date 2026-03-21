package com.mannschaft.app.bulletin.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 返信作成リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class CreateReplyRequest {

    private final Long parentId;

    @NotBlank
    private final String body;
}
