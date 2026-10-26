package com.mannschaft.app.supporter.dto;

/**
 * フォロー（サポーター申請）状態レスポンス。
 *
 * @param status NONE: 申請なし / PENDING: 申請中（承認待ち） / APPROVED: 承認済み（サポーター登録済み）
 */
public record FollowStatusResponse(String status) {

    public static FollowStatusResponse none() {
        return new FollowStatusResponse("NONE");
    }

    public static FollowStatusResponse pending() {
        return new FollowStatusResponse("PENDING");
    }

    public static FollowStatusResponse approved() {
        return new FollowStatusResponse("APPROVED");
    }
}
