package com.mannschaft.app.social.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;

/**
 * フレンドフォルダへのメンバー追加リクエスト DTO。F01.5 の
 * {@code POST /api/v1/teams/{id}/friend-folders/{folderId}/members} で使用する。
 *
 * <p>
 * Phase 1 では 1 リクエスト 1 件ずつの追加を想定。設計書 §5 では複数件一括
 * 追加（{@code team_friend_ids: [55, 56, 58]}）も仕様に含まれているが、冪等性
 * および監査ログの粒度を明確にするためまずは単一 ID ベースから開始する。
 * 複数件一括は次陣で検討。
 * </p>
 */
@Getter
public class AddFolderMemberRequest {

    /** 追加対象の {@code team_friends.id} */
    @NotNull
    private final Long teamFriendId;

    /**
     * Jackson によるデシリアライズ用コンストラクタ。
     *
     * @param teamFriendId 追加対象のフレンド関係 ID
     */
    @JsonCreator
    public AddFolderMemberRequest(
            @JsonProperty("teamFriendId") Long teamFriendId) {
        this.teamFriendId = teamFriendId;
    }
}
