package com.mannschaft.app.shift.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.List;

/**
 * シフト枠一括作成リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class BulkCreateShiftSlotRequest {

    @NotEmpty
    @Size(max = 200)
    @Valid
    private final List<CreateShiftSlotRequest> slots;
}
