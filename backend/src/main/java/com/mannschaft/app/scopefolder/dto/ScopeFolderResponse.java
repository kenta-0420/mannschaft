package com.mannschaft.app.scopefolder.dto;

import java.util.List;

/**
 * スコープフォルダレスポンスDTO。
 * フロントエンドはitemScopeIdsを用いて実際のチーム/組織情報をストアからenrichする。
 */
public record ScopeFolderResponse(
        Long id,
        String name,
        String color,
        int sortOrder,
        List<Long> itemScopeIds
) {
}
