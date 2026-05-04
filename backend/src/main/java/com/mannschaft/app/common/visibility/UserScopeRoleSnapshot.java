package com.mannschaft.app.common.visibility;

import java.util.Map;
import java.util.Set;

/**
 * ユーザーの複数スコープにわたるメンバーシップ・ロール情報のスナップショット値オブジェクト。
 *
 * <p>{@code MembershipBatchQueryService.snapshotForUser(...)} が返す不変のビューで、
 * 1 リクエスト内で複数 {@code ContentVisibilityResolver} が共有・参照する。</p>
 *
 * <p>設計書 {@code docs/features/F00_content_visibility_resolver.md} §10.2 / §11.6
 * のシグネチャに準拠する。</p>
 *
 * @param systemAdmin       SystemAdmin ロール保有
 * @param roleByScope       direct メンバーシップにおけるスコープ → ロール名のマップ
 * @param parentOrgByScope  TEAM スコープ → 親 ORGANIZATION ID のマップ
 *                          （ORGANIZATION スコープは自身が ORG として entry を持つ）
 * @param orgMemberOf       親 ORG での所属を示す {@code ORGANIZATION} スコープ集合
 * @param suspendedOrgIds   非アクティブ（削除済 / SUSPENDED）と判定された親 ORG ID 集合 §11.6
 */
public record UserScopeRoleSnapshot(
        boolean systemAdmin,
        Map<ScopeKey, String> roleByScope,
        Map<ScopeKey, Long> parentOrgByScope,
        Set<ScopeKey> orgMemberOf,
        Set<Long> suspendedOrgIds) {

    /**
     * 防御的コピーは行わない（呼び出し元が不変 Map/Set を渡す前提）。
     * null は空コレクションへ正規化する。
     */
    public UserScopeRoleSnapshot {
        roleByScope = roleByScope != null ? roleByScope : Map.of();
        parentOrgByScope = parentOrgByScope != null ? parentOrgByScope : Map.of();
        orgMemberOf = orgMemberOf != null ? orgMemberOf : Set.of();
        suspendedOrgIds = suspendedOrgIds != null ? suspendedOrgIds : Set.of();
    }

    /**
     * 匿名ユーザー（未ログイン or userId=null）用の空スナップショット。
     */
    public static UserScopeRoleSnapshot empty() {
        return new UserScopeRoleSnapshot(false, Map.of(), Map.of(), Set.of(), Set.of());
    }

    /**
     * SystemAdmin ユーザー用の高速パススナップショット。
     * 後続のメンバーシップ確認 SQL を発行せずに済むため、
     * {@link MembershipBatchQueryService} は SysAdmin 判定後即座に本値を返す。
     *
     * <p>record コンポーネント accessor と衝突するため、static factory は
     * {@code forSystemAdmin} という名称を採用している。</p>
     */
    public static UserScopeRoleSnapshot forSystemAdmin() {
        return new UserScopeRoleSnapshot(true, Map.of(), Map.of(), Set.of(), Set.of());
    }

    public boolean isSystemAdmin() {
        return systemAdmin;
    }

    /**
     * スコープへの直接メンバーシップ（または SystemAdmin）を持つかを返す。
     */
    public boolean isMemberOf(ScopeKey scope) {
        if (scope == null) {
            return false;
        }
        return systemAdmin || roleByScope.containsKey(scope);
    }

    /**
     * スコープにおいて要求ロール以上の権限を持つかを返す。
     * SystemAdmin は常に true。
     */
    public boolean hasRoleOrAbove(ScopeKey scope, String required) {
        if (systemAdmin) {
            return true;
        }
        if (scope == null) {
            return false;
        }
        String role = roleByScope.get(scope);
        return role != null && RolePriority.isAtLeast(role, required);
    }

    /**
     * 当該スコープの「親 ORG」へのメンバーシップを持つかを返す。
     * ORGANIZATION_WIDE 公開判定の中核ロジック。SystemAdmin は常に true。
     */
    public boolean isMemberOfParentOrg(ScopeKey scope) {
        if (systemAdmin) {
            return true;
        }
        if (scope == null) {
            return false;
        }
        Long parentOrg = parentOrgByScope.get(scope);
        if (parentOrg == null) {
            return false;
        }
        return orgMemberOf.contains(new ScopeKey("ORGANIZATION", parentOrg));
    }

    /**
     * 親 ORG が削除済 / SUSPENDED 状態かを返す。
     * 親 ORG が判定不能（マッピング無し）の場合は false。
     * 設計書 §11.6 連鎖ルール: 非アクティブ親 ORG 配下の TEAM コンテンツは
     * SystemAdmin 以外不可視（fail-closed）。
     */
    public boolean isParentOrgInactive(ScopeKey scope) {
        if (scope == null) {
            return false;
        }
        Long parent = parentOrgByScope.get(scope);
        return parent != null && suspendedOrgIds.contains(parent);
    }
}
