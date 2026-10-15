package com.mannschaft.app.survey.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * アンケート複製リクエスト DTO（F05.4 §4.6 duplicate）。
 *
 * <p>新規 DRAFT として複製する。タイトル指定がない場合は「{元タイトル}（コピー）」を自動設定する。</p>
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class DuplicateSurveyRequest {

    /**
     * 新アンケートのタイトル。省略時は「{元タイトル}（コピー）」。
     */
    @Size(min = 1, max = 200)
    private String title;

    /**
     * シリーズ ID。省略時は元アンケートの {@code series_id} を引き継ぐ。
     */
    @JsonProperty("series_id")
    @Size(max = 50)
    private String seriesId;
}
