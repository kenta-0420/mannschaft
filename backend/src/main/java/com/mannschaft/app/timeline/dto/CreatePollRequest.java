package com.mannschaft.app.timeline.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * タイムライン投票作成リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class CreatePollRequest {

    @NotBlank
    @Size(max = 200)
    private final String question;

    @NotEmpty
    @Size(min = 2, max = 10)
    private final List<String> options;

    private final LocalDateTime expiresAt;
}
