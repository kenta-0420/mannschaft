package com.mannschaft.app.scopefolder.dto;

import jakarta.validation.constraints.NotNull;

/**
 * フォルダアイテム追加リクエストDTO。
 */
public record AddFolderItemRequest(
        @NotNull Long scopeId
) {
}
