package com.mannschaft.app.social;

/**
 * フォロー一覧の公開設定。
 * ユーザーがフォロー中・フォロワー一覧を誰に公開するかを制御する。
 */
public enum FollowListVisibility {
    /** 全員に公開（デフォルト） */
    PUBLIC,
    /** 相互フォロー（フレンド）にのみ公開 */
    FRIENDS_ONLY,
    /** 本人のみ（非公開） */
    PRIVATE
}
