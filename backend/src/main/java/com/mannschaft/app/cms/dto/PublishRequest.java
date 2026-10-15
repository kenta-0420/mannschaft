package com.mannschaft.app.cms.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * 公開ステータス変更リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class PublishRequest {

    @NotBlank
    private final String status;

    private final LocalDateTime publishedAt;
    private final String rejectionReason;
}
