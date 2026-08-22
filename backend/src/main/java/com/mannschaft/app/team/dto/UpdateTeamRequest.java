package com.mannschaft.app.team.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.mannschaft.app.common.validation.ValidTimezone;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * チーム更新リクエスト。
 *
 * <p>Jackson でのデシリアライズ互換のため {@code @NoArgsConstructor + @Setter}（CreateTeamRequest と同方針）を採用する。
 * 以前は {@code @RequiredArgsConstructor}（final フィールド + Creator なし）だったため
 * {@code InvalidDefinitionException: no Creators} で PATCH /teams/{slug} が 500 になっていた。</p>
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class UpdateTeamRequest {

    @ValidTimezone
    private String timezone;

    private String name;
    private String nameKana;
    private String nickname1;
    private String nickname2;
    private String template;
    private String prefecture;
    private String city;

    /**
     * F22.1 市 Phase 2 足場C: 都道府県コード（JIS X 0401・2 桁）。null 許容（指定時のみ更新）。
     */
    @Pattern(regexp = "\\d{2}", message = "prefectureCode は 2 桁の数字である必要があります")
    private String prefectureCode;

    /**
     * F22.1 市 Phase 2 足場C: 市区町村コード（JIS X 0402・5 桁）。null 許容（指定時のみ更新）。
     */
    @Pattern(regexp = "\\d{5}", message = "cityCode は 5 桁の数字である必要があります")
    private String cityCode;

    private String visibility;
    private Boolean supporterEnabled;

    /**
     * F15.4 Phase 5-β: Google Maps 埋め込み URL。
     * null 許容。null 以外の場合は Google Maps embed URL パターンに合致する必要がある。
     * 設計書: docs/features/F15.4_phase5_team_public_detail.md §5.2
     */
    @Pattern(
            regexp = "^https://www\\.google\\.com/maps/embed\\?.*$",
            message = "Google Maps 埋め込み URL（https://www.google.com/maps/embed?...）の形式である必要があります"
    )
    private String mapEmbedUrl;

    @NotNull
    private Long version;

    /**
     * 後方互換用コンストラクタ（地域コードなし・既存 11 引数シグネチャ）。
     */
    public UpdateTeamRequest(String name, String nameKana, String nickname1, String nickname2,
                             String template, String prefecture, String city, String visibility,
                             Boolean supporterEnabled, String mapEmbedUrl, Long version) {
        this(null, name, nameKana, nickname1, nickname2, template, prefecture, city,
                null, null, visibility, supporterEnabled, mapEmbedUrl, version);
    }
}
