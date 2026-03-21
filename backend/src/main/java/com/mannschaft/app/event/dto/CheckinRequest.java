package com.mannschaft.app.event.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * スタッフスキャンによるチェックインリクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class CheckinRequest {

    @NotBlank
    private final String qrToken;

    @Size(max = 300)
    private final String note;
}
