package com.mannschaft.app.scopefolder.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * フォルダ並び替えリクエストDTO。
 * orderedIdsに含まれないフォルダIDは並び順変更対象外（無視して残す）。
 */
public record ReorderFoldersRequest(
        @NotNull @Size(min = 1) List<Long> orderedIds
) {
}
