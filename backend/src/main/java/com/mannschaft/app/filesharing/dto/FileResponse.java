package com.mannschaft.app.filesharing.dto;

import com.mannschaft.app.filesharing.FileVisibilityRole;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * ファイルレスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class FileResponse {

    private final Long id;
    private final Long folderId;
    private final String name;
    private final String fileKey;
    private final Long fileSize;
    private final String contentType;
    private final String description;
    private final Long createdBy;
    private final Integer currentVersion;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;
    /** B: ファイル個別の最低可視ロール（null=フォルダ継承）。末尾追加で既存の位置引数コンストラクタ呼び出しへの影響を最小化。 */
    private final FileVisibilityRole minVisibleRole;
    /** C: ファイル個別のダウンロード禁止フラグ。 */
    private final Boolean downloadDisabled;
}
