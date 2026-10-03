package com.mannschaft.app.filesharing.dto;

import com.mannschaft.app.filesharing.FileVisibilityRole;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * フォルダ作成リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class CreateFolderRequest {

    @NotBlank
    @Size(max = 255)
    private final String name;

    @Size(max = 500)
    private final String description;

    private final Long parentId;

    @NotNull
    private final String scopeType;

    /**
     * スコープ ID（teamId / organizationId の文字列）。
     *
     * <p>汎用エンドポイント {@code POST /api/v1/files/folders} で TEAM / ORGANIZATION フォルダを
     * 作成する際の認可・帰属先に使う。スコープを URL パスから受ける既存コントローラ
     * （Team/Org/Personal Folder Controller）では {@code null} のままでよい。</p>
     */
    private final String scopeId;

    /**
     * B: 最低可視ロール（任意）。{@code null} なら所属者全員可視（従来挙動）。
     * 不正な enum 値は Jackson が {@code HttpMessageNotReadableException} → 400 を返す（AC-B7）。
     */
    private final FileVisibilityRole minVisibleRole;

    /** C: ダウンロード禁止フラグ（任意）。{@code null}/false なら DL 可（従来挙動）。 */
    private final Boolean downloadDisabled;
}
