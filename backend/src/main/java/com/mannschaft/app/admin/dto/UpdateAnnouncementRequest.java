package com.mannschaft.app.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * お知らせ更新リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class UpdateAnnouncementRequest {

    @NotBlank
    @Size(max = 200)
    private final String title;

    @NotBlank
    private final String body;

    private final String priority;
    private final String targetScope;
    private final Boolean isPinned;
    private final LocalDateTime expiresAt;
}
