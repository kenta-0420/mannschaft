package com.mannschaft.app.scopefolder.dto;

import com.mannschaft.app.scopefolder.entity.enums.ScopeType;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * フォルダへの一括振り分けリクエストDTO。
 *
 * <p>設計書 F15.3 §5.2.2</p>
 *
 * @param folderId  配置先フォルダ ID
 * @param scopeIds  配置するスコープ ID 一覧（team_id / organization_id）
 * @param scopeType フォルダ・スコープの種別（整合性チェック用）
 */
public record BulkAssignRequest(
        @NotNull Long folderId,
        @NotEmpty List<Long> scopeIds,
        @NotNull ScopeType scopeType
) {
}
