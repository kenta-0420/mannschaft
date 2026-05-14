package com.mannschaft.app.village.dto;

import java.util.List;

/**
 * 投稿主体一覧レスポンス DTO（F17.1 Phase 1 B9 §4.6）。
 *
 * <p>呼び出しユーザーが当該村でなれる投稿主体すべてを返す。
 * 通常は USER 1 件 + 代表チーム/組織数件で合計 1〜数件程度。</p>
 *
 * @param identities 投稿主体エントリ群
 */
public record PostingIdentityListResponse(
        List<PostingIdentityResponse> identities
) {

    public static PostingIdentityListResponse of(List<PostingIdentityResponse> identities) {
        return new PostingIdentityListResponse(identities);
    }
}
