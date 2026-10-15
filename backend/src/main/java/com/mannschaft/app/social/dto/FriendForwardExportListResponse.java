package com.mannschaft.app.social.dto;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

/**
 * 逆転送履歴一覧レスポンス DTO。F01.5 の
 * {@code GET /api/v1/teams/{id}/friend-forward-exports} で使用する。
 *
 * <p>
 * 設計書 §5 ではカーソルベースページング（{@code cursor} / {@code limit}）が
 * 指定されているが、Phase 1 では Phase 3 までの暫定としてオフセットベース
 * ページング情報を返却する。将来カーソル移行時は {@link Pagination} を
 * {@code nextCursor} / {@code hasNext} ベースに差し替える想定。
 * </p>
 */
@Getter
@Builder
public class FriendForwardExportListResponse {

    /** 逆転送履歴一覧 */
    private final List<FriendForwardExportView> data;

    /** ページング情報 */
    private final Pagination pagination;

    /**
     * ページネーションメタ情報。
     */
    @Getter
    @Builder
    public static class Pagination {

        /** 現在のページ番号（0 始まり） */
        private final int page;

        /** 1 ページあたりの件数 */
        private final int size;

        /** 総件数 */
        private final long totalElements;

        /** 総ページ数 */
        private final int totalPages;

        /** 次ページが存在するか */
        private final boolean hasNext;
    }
}
