package com.mannschaft.app.matching.dto;

import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * キャンセルリクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class CancelProposalRequest {

    @Size(max = 500)
    private final String reason;

    private final Boolean mutual;
}
