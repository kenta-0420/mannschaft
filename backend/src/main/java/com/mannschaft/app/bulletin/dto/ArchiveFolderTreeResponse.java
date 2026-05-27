package com.mannschaft.app.bulletin.dto;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

/**
 * 保管庫フォルダツリーレスポンスDTO（設計書 F05.1 §4 GET .../archive/folders）。
 *
 * <p>{@code data} にルートフォルダ（{@code parentId = null}）を {@code displayOrder ASC} で並べ、
 * 各フォルダの {@code children} に子フォルダを再帰ネストする。{@code meta} に保管庫直下件数・
 * フォルダ総数・上限値を含める。</p>
 */
@Getter
@Builder
public class ArchiveFolderTreeResponse {

    /** ルートフォルダ（ツリー）。 */
    private final List<ArchiveFolderResponse> data;

    private final Meta meta;

    /**
     * ツリーのメタ情報。
     */
    @Getter
    @Builder
    public static class Meta {
        /** 保管庫直下（archive_folder_id = NULL かつ is_archived = TRUE）の未分類スレッド数。 */
        private final Long unfiledThreadCount;
        /** 現在のアクティブフォルダ総数。 */
        private final Long totalFolderCount;
        /** ネスト最大階層（= 5）。 */
        private final Integer maxDepth;
        /** フォルダ数上限（= 200）。 */
        private final Integer maxFolderCount;
    }
}
