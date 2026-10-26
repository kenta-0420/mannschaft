package com.mannschaft.app.chat.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * メッセージ編集リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class EditMessageRequest {

    @NotBlank
    private final String body;
}
