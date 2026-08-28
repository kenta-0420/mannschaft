package com.mannschaft.app.role.dto;

import java.util.List;

/**
 * 招待発行できるスコープ一覧レスポンス（F04.12・{@code GET /api/v1/me/invitable-scopes}）。
 *
 * <p>自分が ADMIN/DEPUTY_ADMIN（{@code INVITE_MEMBERS} 権限）として招待発行できるチーム/組織の一覧。
 * 認可の真実源は BE（設計書 B-6）。管理スコープ 0 件でもエラーにせず空配列を返す。
 * JSON 契約は camelCase（設計書 D-12）。</p>
 *
 * @param teams         招待発行できるチーム一覧
 * @param organizations 招待発行できる組織一覧
 */
public record InvitableScopesResponse(
        List<InvitableScope> teams,
        List<InvitableScope> organizations
) {

    /**
     * 招待発行可能スコープの 1 件。
     *
     * @param scopeId スコープ（チーム/組織）ID
     * @param name    表示名
     * @param role    自分のロール（{@code ADMIN} / {@code DEPUTY_ADMIN}）
     */
    public record InvitableScope(
            Long scopeId,
            String name,
            String role
    ) {
    }
}
