package com.mannschaft.app.village.dto;

import java.util.List;

/**
 * 練習試合・審判募集一覧レスポンス（F17.1 Phase 2 U6）。
 *
 * <p>{@link MembershipListResponse} と同じページネーション形式に合わせる。</p>
 */
public record MatchRecruitListResponse(
        List<MatchRecruitResponse> items,
        int page,
        int size,
        long total
) {

    public static MatchRecruitListResponse of(List<MatchRecruitResponse> items, int page, int size, long total) {
        return new MatchRecruitListResponse(items, page, size, total);
    }
}
