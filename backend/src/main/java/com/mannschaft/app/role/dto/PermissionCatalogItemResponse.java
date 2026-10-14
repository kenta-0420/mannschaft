package com.mannschaft.app.role.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 権限カタログの 1 件（{@code GET /api/v1/permissions}）。権限グループの作成・更新で選ぶ権限の一覧に使う。
 */
@Getter
@RequiredArgsConstructor
@Schema(name = "PermissionCatalogItemResponse")
public class PermissionCatalogItemResponse {

    /** 権限 ID（権限グループの作成・更新リクエストの {@code permissionIds} に渡す値）。 */
    private final Long id;

    /** 権限名（コード。例: {@code MANAGE_ORG_AFFILIATION}）。 */
    private final String name;

    /** 表示名。 */
    private final String displayName;

    /** 権限の対象スコープ（{@code TEAM} / {@code ORGANIZATION}）。 */
    private final String scope;

    /** スコープの ADMIN だけが権限グループへ含められる権限か（DEPUTY_ADMIN 以下は 403）。 */
    private final boolean adminOnlyGrantable;
}
