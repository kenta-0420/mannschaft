package com.mannschaft.app.dashboard.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;

import java.util.UUID;

/**
 * F22.1: タグ（所属スコープ）1 件のレスポンス DTO。
 *
 * <p>設計書: docs/features/F22.1_swipe_scope_dashboard/02_api_design.md §3.1</p>
 */
@Builder
public record ScopeTabItemResponse(

        /** チーム ID または 組織 ID（内部 BIGINT）。 */
        @JsonProperty("scope_id") Long scopeId,

        /** チームまたは組織の公開用 UUID（ダッシュボード API の pathVariable に使用）。 */
        @JsonProperty("public_id") UUID publicId,

        /** スコープ種別（TEAM / ORGANIZATION）。 */
        @JsonProperty("scope_type") String scopeType,

        /** 表示名（teams.name / organizations.name）。 */
        @JsonProperty("name") String name,

        /** アイコン URL（なければ null → FE でイニシャルアバター）。 */
        @JsonProperty("avatar_url") String avatarUrl,

        /** 当該スコープの未読合計（バッジ表示用）。 */
        @JsonProperty("unread_count") int unreadCount,

        /** 現在の表示順。 */
        @JsonProperty("sort_order") int sortOrder
) {
}
