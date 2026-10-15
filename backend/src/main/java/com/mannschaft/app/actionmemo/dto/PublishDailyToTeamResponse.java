package com.mannschaft.app.actionmemo.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * F02.5 Phase 3: 当日 WORK メモ一括チームタイムライン投稿レスポンス。
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PublishDailyToTeamResponse {

    @JsonProperty("team_id")
    private Long teamId;

    @JsonProperty("posted_count")
    private int postedCount;
}
