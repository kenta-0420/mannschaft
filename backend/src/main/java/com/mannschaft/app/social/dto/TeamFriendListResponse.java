package com.mannschaft.app.social.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

import java.util.List;

/**
 * フレンドチーム一覧レスポンス DTO。
 *
 * <p>
 * Phase 1 はオフセットページング（{@code Page} ベース）を採用する。
 * Phase 3 で大規模フレンド運用時にはカーソルページングへの移行を想定する
 * （設計書 §5 GET /friends 参照）。
 * </p>
 */
@Getter
@Builder
public class TeamFriendListResponse {

    /** フレンドチーム一覧 */
    private final List<TeamFriendView> data;

    /** ページング情報 */
    private final Pagination pagination;

    /**
     * ページネーションメタ情報。
     */
    @Getter
    @Builder
    public static class Pagination {

        /** 現在のページ番号（0 始まり） */
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        private final int page;

        /** 1 ページあたりの件数 */
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        private final int size;

        /** 総件数 */
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        private final long totalElements;

        /** 総ページ数 */
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        private final int totalPages;

        /** 次ページが存在するか */
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        private final boolean hasNext;
    }
}
