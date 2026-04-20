package com.mannschaft.app.todo.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * マイルストーン内 TODO の並び替えリクエスト（F02.7）。
 *
 * <p>配列の順序がそのまま {@code position}（0, 1, 2, 3 ...）となる。最大 100 件。</p>
 */
public record ReorderTodosRequest(
        @NotNull
        @Size(min = 1, max = 100, message = "todoIds は 1〜100 件で指定してください")
        List<Long> todoIds
) {}
