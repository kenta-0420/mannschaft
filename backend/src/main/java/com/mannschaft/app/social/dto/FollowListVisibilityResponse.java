package com.mannschaft.app.social.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * フォロー一覧公開設定レスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class FollowListVisibilityResponse {

    /** 公開設定（"PUBLIC" / "FRIENDS_ONLY" / "PRIVATE"） */
    private final String visibility;
}
