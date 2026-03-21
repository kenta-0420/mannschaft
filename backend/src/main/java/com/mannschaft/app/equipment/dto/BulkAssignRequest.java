package com.mannschaft.app.equipment.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDate;
import java.util.List;

/**
 * 一括貸出リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class BulkAssignRequest {

    @NotEmpty
    @Size(max = 20)
    @Valid
    private final List<BulkAssignEntry> assignments;

    private final LocalDate expectedReturnAt;

    @jakarta.validation.constraints.Size(max = 300)
    private final String note;

    /**
     * 一括貸出の各エントリ。
     */
    @Getter
    @RequiredArgsConstructor
    public static class BulkAssignEntry {

        @jakarta.validation.constraints.NotNull
        private final Long assignedToUserId;

        @jakarta.validation.constraints.NotNull
        @jakarta.validation.constraints.Min(1)
        private final Integer quantity;
    }
}
