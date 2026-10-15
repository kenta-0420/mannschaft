package com.mannschaft.app.social.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;

/**
 * フレンド関係の公開設定変更リクエスト DTO。F01.5 の
 * {@code PATCH /api/v1/teams/{id}/friends/{teamFriendId}/visibility} で使用する。
 *
 * <p>
 * 公開設定の変更は ADMIN のみ可能。Phase 1 は単独承認型として
 * どちらかの ADMIN が {@code TRUE} に切り替えれば公開になる。Phase 3 で
 * 両チーム双方の承認フローに昇格予定。
 * </p>
 */
@Getter
public class SetVisibilityRequest {

    /** 公開フラグ。{@code TRUE} で公開、{@code FALSE} で非公開。 */
    @NotNull
    private final Boolean isPublic;

    /**
     * Jackson によるデシリアライズ用コンストラクタ。
     *
     * @param isPublic 公開フラグ
     */
    @JsonCreator
    public SetVisibilityRequest(
            @JsonProperty("isPublic") Boolean isPublic) {
        this.isPublic = isPublic;
    }
}
