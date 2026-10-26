package com.mannschaft.app.team.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Getter;

/**
 * チームカスタムフィールドレスポンス DTO。
 */
@Getter
@Builder
public class TeamCustomFieldResponse {

    private Long id;

    @JsonProperty("team_id")
    private Long teamId;

    private String label;

    private String value;

    @JsonProperty("display_order")
    private Integer displayOrder;

    @JsonProperty("is_visible")
    private Boolean isVisible;

    /**
     * ADMIN/DEPUTY_ADMIN が visibilityPreview=true で取得した場合のみ付与される。
     * 一般メンバー・非メンバー取得時は null。
     */
    @JsonProperty("is_publicly_visible")
    private Boolean isPubliclyVisible;
}
