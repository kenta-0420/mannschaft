package com.mannschaft.app.parking.dto;

import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDate;

/**
 * 譲渡希望更新リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class UpdateListingRequest {

    @Size(max = 500)
    private final String reason;

    private final LocalDate desiredTransferDate;
}
