package com.mannschaft.app.actionmemo.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * F02.5 Phase 3: メモ個別チームタイムライン投稿リクエスト。
 *
 * <p>{@code POST /api/v1/action-memos/{id}/publish-to-team} で使用。</p>
 * <p>team_id 省略時は settings.default_post_team_id を使用。どちらも NULL なら 400。</p>
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class PublishToTeamRequest {

    /**
     * 投稿先チームID。省略時は settings.default_post_team_id を使用。
     */
    @JsonProperty("team_id")
    private Long teamId;

    /**
     * 本文末尾に追記するコメント（任意）。
     * XSS 対策として HtmlSanitizer でサニタイズされる。
     */
    @JsonProperty("extra_comment")
    private String extraComment;
}
