package com.mannschaft.app.schedule.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.List;

/**
 * 出欠一括登録リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class BulkAttendanceRequest {

    @NotEmpty
    private final List<BulkAttendanceItem> attendances;

    /**
     * 一括出欠の個別アイテム。
     */
    public record BulkAttendanceItem(
            Long userId,
            @NotNull String status,
            String comment
    ) {}
}
