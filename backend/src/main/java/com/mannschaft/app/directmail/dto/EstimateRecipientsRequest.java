package com.mannschaft.app.directmail.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 配信対象数見積リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class EstimateRecipientsRequest {

    @NotBlank
    @Size(max = 20)
    private final String recipientType;

    private final String recipientFilter;
}
