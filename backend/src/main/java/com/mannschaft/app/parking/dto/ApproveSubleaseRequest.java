package com.mannschaft.app.parking.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * サブリース承認リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class ApproveSubleaseRequest {

    @NotNull
    private final Long applicationId;
}
