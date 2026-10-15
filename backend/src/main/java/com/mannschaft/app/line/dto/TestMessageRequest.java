package com.mannschaft.app.line.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * テストメッセージ送信リクエスト。
 */
@Getter
@RequiredArgsConstructor
public class TestMessageRequest {

    @NotBlank
    @Size(max = 50)
    private final String lineUserId;

    @NotBlank
    @Size(max = 500)
    private final String message;
}
