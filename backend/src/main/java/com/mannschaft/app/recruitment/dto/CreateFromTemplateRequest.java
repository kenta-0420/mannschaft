package com.mannschaft.app.recruitment.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * F03.11 募集型予約 Phase 3: テンプレートから募集枠を作成するリクエスト。
 * startAt / endAt は必須。その他は null の場合テンプレートのデフォルト値を使用する。
 */
@Getter
@RequiredArgsConstructor
public class CreateFromTemplateRequest {

    @NotNull
    private final LocalDateTime startAt;

    @NotNull
    private final LocalDateTime endAt;

    /** null の場合、テンプレートの defaultApplicationDeadlineHours を使って startAt から計算する */
    private final LocalDateTime applicationDeadline;

    /** null の場合、テンプレートの defaultAutoCancelHours を使って applicationDeadline から計算する */
    private final LocalDateTime autoCancelAt;

    /** null の場合、テンプレートの title を使用する */
    private final String title;

    /** null の場合、テンプレートの description を使用する */
    private final String description;

    /** null の場合、テンプレートの defaultCapacity を使用する */
    private final Integer capacity;

    /** null の場合、テンプレートの defaultMinCapacity を使用する */
    private final Integer minCapacity;

    /** null の場合、テンプレートの defaultLocation を使用する */
    private final String location;

    /** null の場合、テンプレートの defaultImageUrl を使用する */
    private final String imageUrl;
}
