package com.mannschaft.app.admin.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * フィードバックステータス変更リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class FeedbackStatusRequest {

    @NotBlank
    private final String status;
}
