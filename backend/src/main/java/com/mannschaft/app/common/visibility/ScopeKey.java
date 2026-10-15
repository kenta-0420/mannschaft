package com.mannschaft.app.common.visibility;

import java.util.Objects;

/**
 * スコープ (TEAM / ORGANIZATION) を一意に識別する複合キー。
 *
 * <p>設計書: {@code docs/features/F00_content_visibility_resolver.md} §4.6 / §5.1.1。
 *
 * <p>{@code MembershipBatchQueryService} や {@code ScopeAncestorResolver} など、
 * バッチで複数スコープのメンバーシップ・親 ORG 解決を行う API のキーとして利用する。
 *
 * <p>{@code equals}/{@code hashCode} は record のデフォルト実装 (構造的等価) を利用する。
 *
 * <p>将来の多テナント isolation への拡張余地は設計書 §17.Q14 を参照
 * (今回は対応せず、tenantId フィールドは含めない)。
 *
 * @param scopeType {@code "TEAM"} または {@code "ORGANIZATION"} のいずれか
 * @param scopeId   対応する team_id または organization_id ({@code null} 不可)
 */
public record ScopeKey(String scopeType, Long scopeId) {

    public ScopeKey {
        Objects.requireNonNull(scopeType, "scopeType must not be null");
        Objects.requireNonNull(scopeId, "scopeId must not be null");
    }
}
