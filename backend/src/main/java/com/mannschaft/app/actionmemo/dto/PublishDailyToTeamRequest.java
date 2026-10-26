package com.mannschaft.app.actionmemo.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * F02.5 Phase 3: 当日 WORK メモ一括チームタイムライン投稿リクエスト。
 *
 * <p>{@code POST /api/v1/action-memos/publish-daily-to-team} で使用。</p>
 * <p>team_id 省略時は settings.default_post_team_id を使用。どちらも NULL なら 400。</p>
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class PublishDailyToTeamRequest {

    /**
     * 投稿先チームID。省略時は settings.default_post_team_id を使用。
     */
    @JsonProperty("team_id")
    private Long teamId;
}
