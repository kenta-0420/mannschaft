package com.mannschaft.app.organization.dto;

import lombok.Builder;
import lombok.Getter;

/**
 * 子組織1件のレスポンス DTO（GET /api/v1/organizations/{id}/children 用）。
 *
 * <p>非メンバーから見える子組織のみを返却する（PRIVATE 子組織は呼び出し者が直接所属メンバーの場合のみ含む）。
 * アーカイブ済みは {@link #archived} フラグで識別可能（除外しない）。</p>
 */
@Getter
@Builder
public class ChildOrganizationResponse {

    private final Long id;

    private final String name;

    private final String nickname1;

    private final String iconUrl;

    /** 公開範囲（PUBLIC/PRIVATE） */
    private final String visibility;

    /** 子組織の直接所属メンバー数 */
    private final int memberCount;

    /** {@code true} の場合、{@code archived_at IS NOT NULL}（アーカイブ済み） */
    private final boolean archived;
}
