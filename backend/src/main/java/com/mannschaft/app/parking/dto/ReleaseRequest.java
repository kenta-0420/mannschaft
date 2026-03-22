package com.mannschaft.app.parking.dto;

import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 区画解除リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class ReleaseRequest {

    @Size(max = 200)
    private final String releaseReason;
}
