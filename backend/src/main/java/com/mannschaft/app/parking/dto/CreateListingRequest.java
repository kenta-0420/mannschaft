package com.mannschaft.app.parking.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDate;

/**
 * 譲渡希望作成リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class CreateListingRequest {

    @NotNull
    private final Long spaceId;

    @Size(max = 500)
    private final String reason;

    private final LocalDate desiredTransferDate;
}
