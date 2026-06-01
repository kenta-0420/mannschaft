package com.mannschaft.app.team.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * チーム作成リクエスト。
 */
@Getter
@RequiredArgsConstructor
public class CreateTeamRequest {

    @NotBlank
    private final String name;

    private final String template;
    private final String prefecture;
    private final String city;
    private final String visibility;

    /**
     * F22.1 市 Phase 2 足場C: 都道府県コード（JIS X 0401・2 桁）。
     * <p>自由入力の {@link #prefecture}（名称）とは別の構造化フィルタ用キー。null 許容。</p>
     */
    @Pattern(regexp = "\\d{2}", message = "prefectureCode は 2 桁の数字である必要があります")
    private final String prefectureCode;

    /**
     * F22.1 市 Phase 2 足場C: 市区町村コード（JIS X 0402・5 桁）。
     * <p>自由入力の {@link #city}（名称）とは別の構造化フィルタ用キー。null 許容。</p>
     */
    @Pattern(regexp = "\\d{5}", message = "cityCode は 5 桁の数字である必要があります")
    private final String cityCode;

    /**
     * 後方互換用コンストラクタ（地域コードなし）。
     */
    public CreateTeamRequest(String name, String template, String prefecture,
                             String city, String visibility) {
        this(name, template, prefecture, city, visibility, null, null);
    }
}
