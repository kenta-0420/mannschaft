package com.mannschaft.app.social.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;

/**
 * チーム間フォローリクエスト DTO。F01.5 の
 * {@code POST /api/v1/teams/{id}/friends/follow} で使用する。
 *
 * <p>
 * 自チームが他チームをフォローする際のパラメータを保持する。
 * 相互フォロー成立時にフレンド関係へ自動昇格する。
 * </p>
 */
@Getter
public class FollowTeamRequest {

    /** フォロー先のチーム ID */
    @NotNull
    private final Long targetTeamId;

    /** 相手チーム管理者に表示される任意の挨拶コメント（最大 300 文字） */
    @Size(max = 300)
    private final String comment;

    /**
     * Jackson によるデシリアライズ用コンストラクタ。JSON 側のスネークケース
     * {@code target_team_id} を明示的にマッピングする。
     *
     * @param targetTeamId フォロー先チーム ID
     * @param comment      任意の挨拶コメント
     */
    @JsonCreator
    public FollowTeamRequest(
            @JsonProperty("targetTeamId") Long targetTeamId,
            @JsonProperty("comment") String comment) {
        this.targetTeamId = targetTeamId;
        this.comment = comment;
    }
}
