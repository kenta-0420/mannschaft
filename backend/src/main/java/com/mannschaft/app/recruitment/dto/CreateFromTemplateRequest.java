package com.mannschaft.app.recruitment.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * F03.11 募集型予約 Phase 3: テンプレートから募集枠を作成するリクエスト。
 * templateId と startAt は必須。その他は null の場合テンプレートのデフォルト値を使用する。
 */
@Getter
@RequiredArgsConstructor
public class CreateFromTemplateRequest {

    /** 使用するテンプレートの ID（必須）。 */
    @NotNull
    private final Long templateId;

    /** 開催開始日時（必須）。 */
    @NotNull
    private final LocalDateTime startAt;

    /** null の場合、テンプレートの defaultDurationMinutes を使って startAt から計算する */
    private final LocalDateTime endAt;

    /** null の場合、テンプレートの defaultApplicationDeadlineHours を使って startAt から計算する */
    private final LocalDateTime applicationDeadline;

    /** null の場合、テンプレートの defaultAutoCancelHours を使って applicationDeadline から計算する */
    private final LocalDateTime autoCancelAt;

    /** null の場合、テンプレートの defaultCapacity を使用する */
    private final Integer capacity;

    /** null の場合、テンプレートの defaultMinCapacity を使用する */
    private final Integer minCapacity;
}
