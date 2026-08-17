package com.mannschaft.app.returnstayplan.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;

/** F02.11 作成契約の最小骨格。入力検証は出陣で実装する。 */
public record ReturnStayPlanCreateRequest(
        @NotBlank String planType,
        @NotNull Boolean isPublished,
        @Valid @NotNull Location location,
        @NotNull LocalDate startDate,
        @NotNull LocalDate endDate,
        @NotNull @Size(max = 20) List<@NotNull Long> teamIds) {

    /** 国内・海外共通の場所契約。 */
    public record Location(@NotBlank String countryCode, String prefectureCode, String regionName) {
    }
}
