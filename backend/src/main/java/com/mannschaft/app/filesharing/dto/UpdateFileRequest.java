package com.mannschaft.app.filesharing.dto;

import com.mannschaft.app.filesharing.FileVisibilityRole;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * ファイル更新リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class UpdateFileRequest {

    @Size(max = 255)
    private final String name;

    @Size(max = 500)
    private final String description;

    private final Long folderId;

    /** B: ファイル個別の最低可視ロール（任意・PATCH 意味論で指定時のみ更新）。不正 enum 値は 400。 */
    private final FileVisibilityRole minVisibleRole;

    /** C: ファイル個別のダウンロード禁止フラグ（任意・PATCH 意味論で指定時のみ更新）。 */
    private final Boolean downloadDisabled;
}
