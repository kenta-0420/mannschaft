package com.mannschaft.app.filesharing.dto;

import com.mannschaft.app.filesharing.FileVisibilityRole;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.List;

/**
 * F05.5 フォルダ詳細レスポンス DTO（{@code GET /api/v1/files/folders/{folderId}}）。
 *
 * <p>フロントエンド（{@code FileBrowser.vue}）が要求する「当該フォルダのメタ ＋ 直下のサブフォルダ／
 * ファイル ＋ ルートからのパンくず」をまとめて返す。{@code scopeId} は文字列で返す
 * （TEAM→teamId / ORGANIZATION→organizationId / PERSONAL→userId /
 * TOURNAMENT(_DIVISION)→scopeRefId を文字列化）。</p>
 *
 * <p>OpenAPI スキーマ名は他ドメインの汎用名（FolderSummary / UserRef 等）との衝突を避けるため
 * {@code FileSharing*} で固定する（springdoc の同名 nested schema 畳み込み対策）。</p>
 */
@Schema(name = "FileSharingFolderDetail", description = "ファイル共有フォルダ詳細")
public record FolderDetailResponse(
        Long id,
        String scopeType,
        String scopeId,
        Long parentId,
        String name,
        String description,
        UserRef createdBy,
        Integer fileCount,
        Integer subfolderCount,
        FileVisibilityRole minVisibleRole,
        Boolean downloadDisabled,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        List<FolderSummary> subfolders,
        List<FileSummary> files,
        List<BreadcrumbItem> breadcrumbs
) {

    /**
     * サブフォルダ要約（フロントの {@code SharedFolder} 形に一致）。
     *
     * <p>{@code subfolderCount} はサブフォルダの孫数であり、画面で未使用かつ N+1 を避けるため
     * 解決しない（{@code null}）。当該フォルダ自身の {@code subfolderCount} は
     * {@link FolderDetailResponse#subfolderCount()} に正しい件数が入る。</p>
     */
    @Schema(name = "FileSharingFolderSummary", description = "ファイル共有フォルダ要約")
    public record FolderSummary(
            Long id,
            String scopeType,
            String scopeId,
            Long parentId,
            String name,
            String description,
            UserRef createdBy,
            Integer fileCount,
            Integer subfolderCount,
            FileVisibilityRole minVisibleRole,
            Boolean downloadDisabled,
            LocalDateTime createdAt,
            LocalDateTime updatedAt
    ) {
    }

    /**
     * ファイル要約（フロントの {@code SharedFile} 形に一致）。
     *
     * <p>{@code fileName}＝entity.name、{@code mimeType}＝entity.contentType、
     * {@code versionCount}＝entity.currentVersion。{@code originalFileName} は別カラムを持たないため
     * {@code name} を流用する。{@code currentVersionId} は当該詳細では解決しない（{@code null}）。
     * {@code tags} は空配列、{@code downloadCount} は {@code 0} を返す（本 EP の責務外）。</p>
     */
    @Schema(name = "FileSharingFileSummary", description = "ファイル共有ファイル要約")
    public record FileSummary(
            Long id,
            Long folderId,
            String fileName,
            String originalFileName,
            Long fileSize,
            String mimeType,
            String description,
            UserRef uploadedBy,
            Integer versionCount,
            Long currentVersionId,
            List<String> tags,
            Integer downloadCount,
            FileVisibilityRole minVisibleRole,
            Boolean downloadDisabled,
            LocalDateTime createdAt,
            LocalDateTime updatedAt
    ) {
    }

    /** 作成者／アップロード者の最小参照（id ＋ 表示名）。 */
    @Schema(name = "FileSharingUserRef", description = "ファイル共有ユーザー参照")
    public record UserRef(
            Long id,
            String displayName
    ) {
    }

    /** パンくず 1 要素（ルート→当該フォルダの順で並ぶ）。 */
    @Schema(name = "FileSharingBreadcrumb", description = "ファイル共有パンくず")
    public record BreadcrumbItem(
            Long id,
            String name
    ) {
    }
}
