package com.mannschaft.app.actionmemo.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * F02.5 Phase 3: メモ個別チームタイムライン投稿レスポンス。
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PublishToTeamResponse {

    @JsonProperty("timeline_post_id")
    private Long timelinePostId;

    @JsonProperty("team_id")
    private Long teamId;

    @JsonProperty("memo_id")
    private Long memoId;
}
