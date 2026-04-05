package com.mannschaft.app.parking.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.List;

/**
 * 区画一括作成リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class BulkCreateSpaceRequest {

    @NotEmpty
    @Size(max = 50)
    @Valid
    private final List<CreateSpaceRequest> spaces;
}
