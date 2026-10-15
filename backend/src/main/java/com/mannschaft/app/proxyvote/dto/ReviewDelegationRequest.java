package com.mannschaft.app.proxyvote.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 委任状承認/却下リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class ReviewDelegationRequest {

    @NotBlank
    private final String status;
}
