package com.mannschaft.app.facility.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.List;

/**
 * 施設一括作成リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class BulkCreateFacilityRequest {

    @NotEmpty
    @Size(max = 20)
    @Valid
    private final List<CreateFacilityRequest> facilities;
}
