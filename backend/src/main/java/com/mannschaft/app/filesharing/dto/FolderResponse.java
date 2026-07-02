package com.mannschaft.app.filesharing.dto;

import com.mannschaft.app.filesharing.FileVisibilityRole;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * フォルダレスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class FolderResponse {

    private final Long id;
    private final String scopeType;
    private final Long teamId;
    private final Long organizationId;
    private final Long userId;
    private final Long parentId;
    private final String name;
    private final String description;
    private final Long createdBy;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;
    /** B: 最低可視ロール（null=所属者全員可視）。末尾追加で既存の位置引数コンストラクタ呼び出しへの影響を最小化。 */
    private final FileVisibilityRole minVisibleRole;
    /** C: ダウンロード禁止フラグ。 */
    private final Boolean downloadDisabled;
}
