package com.mannschaft.app.village.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.List;

/**
 * F17.1 Phase 3-β — 寄合作成リクエスト。
 *
 * <p>{@code description} / {@code location} は省略可。
 * {@code candidateDates} は必須で、最低 1 件以上指定する。
 * 候補日の重複チェック・日付検証は Service 側で行う。</p>
 */
public record MeetupCreateRequest(
        @NotBlank @Size(max = 200) String title,
        @Size(max = 5000) String description,
        @Size(max = 300) String location,
        @NotEmpty @Size(max = 30) List<LocalDate> candidateDates) {
}
