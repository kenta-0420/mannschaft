package com.mannschaft.app.equipment.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.List;

/**
 * 一括返却リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class BulkReturnRequest {

    @NotEmpty
    @Size(max = 20)
    private final List<Long> assignmentIds;

    @jakarta.validation.constraints.Size(max = 300)
    private final String note;
}
