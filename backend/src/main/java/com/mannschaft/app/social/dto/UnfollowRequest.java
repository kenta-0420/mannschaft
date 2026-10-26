package com.mannschaft.app.social.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;

/**
 * フォロー解除リクエスト DTO。F01.5 の
 * {@code DELETE /api/v1/teams/{id}/friends/follow/{targetTeamId}} で使用する。
 *
 * <p>
 * リクエストボディ省略時は {@link PastForwardHandling#KEEP}（保持）が適用される。
 * </p>
 */
@Getter
public class UnfollowRequest {

    /**
     * 過去転送投稿の扱い。省略時は {@link PastForwardHandling#KEEP}。
     * 値は {@link PastForwardHandling} の列挙定義参照。
     */
    private final PastForwardHandling pastForwardHandling;

    /**
     * Jackson によるデシリアライズ用コンストラクタ。
     *
     * @param pastForwardHandling 過去転送投稿の扱い（null の場合は {@code KEEP} が適用される）
     */
    @JsonCreator
    public UnfollowRequest(
            @JsonProperty("pastForwardHandling") PastForwardHandling pastForwardHandling) {
        this.pastForwardHandling = pastForwardHandling;
    }

    /**
     * 実効モードを取得する。ボディ未指定（null）時は {@link PastForwardHandling#KEEP} を返す。
     *
     * @return 実効モード（常に非 null）
     */
    public PastForwardHandling getEffectiveMode() {
        return this.pastForwardHandling != null
                ? this.pastForwardHandling
                : PastForwardHandling.KEEP;
    }
}
