package com.mannschaft.app.village.dto;

import java.util.List;

/**
 * 練習試合・審判募集一覧レスポンス（F17.1 Phase 2 U6）。
 *
 * <p>本レスポンスは {@code {items, page, size, total}} の独自エンベロープであり、
 * Spring の {@code Page} 形状でも {@link MembershipListResponse}
 * （{@code {content, page, size, totalElements, totalPages}}）でもない点に注意。</p>
 *
 * @param items 募集一覧
 * @param page  現在のページ番号（0 始まり）
 * @param size  1 ページあたりの件数
 * @param total 総件数
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
