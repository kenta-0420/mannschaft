package com.mannschaft.app.activity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Map;

/**
 * 活動記録の下書き（DRAFT）作成リクエストDTO（F06.4 下書き対応）。
 *
 * <p>AC-8: 最小項目は {@code title} + {@code activityDate} のみ。テンプレートその他は任意。
 * {@link CreateActivityRequest} と異なり {@code templateId} は必須ではない（NULL 許容）。</p>
 *
 * <p>Jackson デシリアライズ: 単一コンストラクタ（{@code @RequiredArgsConstructor}）のため
 * {@code @JsonCreator} 不要（{@link CreateActivityRequest} と同一方式）。</p>
 */
@Getter
@RequiredArgsConstructor
public class CreateDraftActivityRequest {

    @NotBlank
    @Size(max = 200)
    private final String title;

    @NotNull
    private final LocalDate activityDate;

    /** 任意。指定した場合のみテンプレート存在チェックを行う。 */
    private final Long templateId;

    private final LocalTime activityTimeStart;

    private final LocalTime activityTimeEnd;

    @Size(max = 10000)
    private final String description;

    private final Map<String, Object> fieldValues;

    private final String visibility;
}
