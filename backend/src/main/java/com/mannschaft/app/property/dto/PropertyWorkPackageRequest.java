package com.mannschaft.app.property.dto;

import com.mannschaft.app.property.WorkPackageVisibility;
import com.mannschaft.app.property.WorkType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.List;

/**
 * 物件履歴パッケージ作成・更新リクエスト DTO（F09.13 Phase 1-δ）。
 *
 * <p>設計書 {@code docs/features/F09.13_property_history.md} §4 履歴パッケージ API
 * および §6.6 入力バリデーションに対応。{@code version} は楽観的ロック用（PUT 時必須、
 * POST 時は {@code 0}）。</p>
 */
public record PropertyWorkPackageRequest(
        Long dwellingUnitId,

        @NotNull
        WorkType workType,

        @Size(max = 50)
        String category,

        @NotBlank
        @Size(max = 200)
        String title,

        @Size(max = 10_000)
        String description,

        Long incidentId,

        LocalDate incidentDate,

        @Size(max = 5_000)
        String incidentNarrative,

        LocalDate plannedStartDate,
        LocalDate plannedEndDate,
        LocalDate actualStartDate,
        LocalDate actualEndDate,

        Long vendorId,

        @PositiveOrZero
        Long estimatedAmount,

        @PositiveOrZero
        Long contractAmount,

        @PositiveOrZero
        Long actualAmount,

        @Pattern(regexp = "^[A-Z]{3}$")
        String currency,

        Long budgetTransactionId,

        LocalDate warrantyUntil,

        @NotNull
        Boolean isDisclosable,

        @NotNull
        WorkPackageVisibility visibility,

        @Size(max = 20)
        List<@Size(min = 1, max = 30) String> tags,

        /** 楽観的ロック用 version。PUT 時必須、POST 時は {@code 0L} を渡す。 */
        @NotNull
        Long version) {
}
