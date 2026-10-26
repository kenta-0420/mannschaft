package com.mannschaft.app.actionmemo.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * F02.5 Phase 3: 行動メモの投稿先として選択可能なチームレスポンス。
 *
 * <p>{@code GET /api/v1/action-memos/available-teams} で返されるリストの要素。</p>
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AvailableTeamResponse {

    private Long id;

    private String name;

    /**
     * settings.default_post_team_id と一致する場合 true。
     */
    @JsonProperty("is_default")
    private boolean isDefault;
}
