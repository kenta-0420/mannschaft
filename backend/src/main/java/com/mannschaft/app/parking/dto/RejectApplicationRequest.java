package com.mannschaft.app.parking.dto;

import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 申請拒否リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class RejectApplicationRequest {

    @Size(max = 500)
    private final String rejectionReason;
}
