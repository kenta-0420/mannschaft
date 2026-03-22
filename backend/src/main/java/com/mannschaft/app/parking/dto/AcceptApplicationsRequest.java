package com.mannschaft.app.parking.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * 申請受付開始リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class AcceptApplicationsRequest {

    @NotNull
    private final String allocationMethod;

    private final LocalDateTime applicationDeadline;
}
