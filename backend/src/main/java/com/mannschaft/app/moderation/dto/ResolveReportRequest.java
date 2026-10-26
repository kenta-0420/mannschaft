package com.mannschaft.app.moderation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * 通報対応リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class ResolveReportRequest {

    @NotBlank
    private final String actionType;

    @Size(max = 2000)
    private final String note;

    private final LocalDateTime freezeUntil;

    @Size(max = 100)
    private final String guidelineSection;
}
