package com.mannschaft.app.resident.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.List;

/**
 * 居室一括登録リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class BatchCreateDwellingUnitRequest {

    @NotEmpty
    @Valid
    private final List<CreateDwellingUnitRequest> units;
}
