package com.mannschaft.app.village.dto;

import java.util.List;

/**
 * 村メンバー一覧レスポンス（ページネーション付き）。
 *
 * @param content       メンバー一覧
 * @param page          現在のページ番号（0 始まり）
 * @param size          1 ページあたりの件数
 * @param totalElements 総件数
 * @param totalPages    総ページ数
 */
public record MembershipListResponse(
        List<MembershipResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages
) {

    public static MembershipListResponse of(List<MembershipResponse> content,
                                            int page,
                                            int size,
                                            long totalElements) {
        int totalPages = size > 0 ? (int) Math.ceil((double) totalElements / size) : 0;
        return new MembershipListResponse(content, page, size, totalElements, totalPages);
    }
}
