package com.mannschaft.app.safetycheck.dto;

import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * フォローアップ更新リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class FollowupUpdateRequest {

    private final String followupStatus;

    private final Long assignedTo;

    @Size(max = 500)
    private final String note;
}
