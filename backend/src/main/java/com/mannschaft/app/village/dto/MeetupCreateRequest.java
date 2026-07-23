package com.mannschaft.app.village.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * F17.1 Phase 3-β — 寄合作成リクエスト。
 *
 * <p>{@code description} / {@code location} は省略可。
 * {@code candidateDates} は必須で、最低 1 件以上指定する。
 * 各候補日は {@code {date（必須）, time（任意・終日=null）}} の object。（#2357）
 * 候補日の重複チェック（(date, time) ペア）・日付検証は Service 側で行う。</p>
 */
public record MeetupCreateRequest(
        @NotBlank @Size(max = 200) String title,
        @Size(max = 5000) String description,
        @Size(max = 300) String location,
        @NotEmpty @Size(max = 30) @Valid List<MeetupCandidateDateInput> candidateDates,
        // F17.2 追補: GOING 定員（任意・null=無制限）。指定時は 1 以上（0/負値は 400）。
        @Min(1) Integer capacity) {

    /**
     * 後方互換コンストラクタ（capacity 未指定＝無制限）。
     * capacity 追加以前の 4 引数呼び出し（既存テスト等）を壊さないためのデリゲート。
     */
    public MeetupCreateRequest(String title, String description, String location,
                               List<MeetupCandidateDateInput> candidateDates) {
        this(title, description, location, candidateDates, null);
    }
}
