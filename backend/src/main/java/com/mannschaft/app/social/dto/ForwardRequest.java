package com.mannschaft.app.social.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;

/**
 * フレンド投稿転送リクエスト DTO。F01.5 の
 * {@code POST /api/v1/teams/{id}/friend-feed/{postId}/forward} で使用する。
 *
 * <p>
 * Phase 1 では {@link ForwardTarget#MEMBER} 固定。Service 層で
 * {@link ForwardTarget#MEMBER_AND_SUPPORTER} を拒否する。
 * </p>
 */
@Getter
public class ForwardRequest {

    /** 配信範囲。Phase 1 は MEMBER 固定。 */
    @NotNull
    private final ForwardTarget target;

    /** 管理者コメント（任意、最大 500 文字） */
    @Size(max = 500)
    private final String comment;

    /**
     * Jackson によるデシリアライズ用コンストラクタ。
     *
     * @param target  配信範囲
     * @param comment 管理者コメント
     */
    @JsonCreator
    public ForwardRequest(
            @JsonProperty("target") ForwardTarget target,
            @JsonProperty("comment") String comment) {
        this.target = target;
        this.comment = comment;
    }
}
