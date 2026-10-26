package com.mannschaft.app.parking.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDate;
import java.util.List;

/**
 * 一括割り当てリクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class BulkAssignRequest {

    @NotEmpty
    @Size(max = 50)
    @Valid
    private final List<BulkAssignItem> assignments;

    @Getter
    @RequiredArgsConstructor
    public static class BulkAssignItem {

        @NotNull
        private final Long spaceId;

        @NotNull
        private final Long userId;

        private final Long vehicleId;

        private final LocalDate contractStartDate;

        private final LocalDate contractEndDate;
    }
}
