package com.mannschaft.app.tournament.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * ステータス変更リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class StatusChangeRequest {

    @NotBlank
    private final String status;
}
