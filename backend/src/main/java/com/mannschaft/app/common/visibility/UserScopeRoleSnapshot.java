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
 * @param systemAdmin              SystemAdmin ロール保有
 * @param roleByScope             direct メンバーシップにおけるスコープ → ロール名のマップ
 * @param parentOrgByScope        TEAM スコープ → 親 ORGANIZATION ID のマップ
 *                                （ORGANIZATION スコープは自身が ORG として entry を持つ）
 * @param orgMemberOf             親 ORG での所属を示す {@code ORGANIZATION} スコープ集合
 *                                （= 直接所属。{@code ORGANIZATION_WIDE}（上向き 1 段）判定に用いる）
 * @param suspendedOrgIds         非アクティブ（削除済 / SUSPENDED）と判定された
 *                                「{@code parentOrgByScope} に現れる ORG（親 ORG / 当該 ORG）」の ID 集合 §11.6
 * @param descendantMemberOfOrgIds viewer が「再帰的配下メンバー（直属（全子孫組織）∪ 配下 ACTIVE チーム）」
 *                                である ORG の ID 集合（フェーズ M2 / {@code ORGANIZATION_AND_DESCENDANTS}
 *                                下向き再帰判定に用いる）。{@code orgMemberOf}（直接所属）とは
 *                                <strong>別フィールド</strong>であり、組織メンバー定義（G3）には影響しない。
 * @param orgRoleByScope          親 ORG（{@code ORGANIZATION} スコープ）への<strong>直接所属ロール名</strong>の
 *                                マップ（CMP-017b で追加）。{@code orgMemberOf} が「所属しているか（真偽）」
 *                                しか持たず閾値評価ができなかったため、同じ一括取得結果からロール名を
 *                                取り出して保持する。{@code roleByScope}（direct スコープ）とは
 *                                <strong>別フィールド</strong>であり、既存判定には一切影響しない。
 *                                キーは常に {@code ScopeKey("ORGANIZATION", orgId)}。
 */
public record UserScopeRoleSnapshot(
        boolean systemAdmin,
        Map<ScopeKey, String> roleByScope,
        Map<ScopeKey, Long> parentOrgByScope,
        Set<ScopeKey> orgMemberOf,
        Set<Long> suspendedOrgIds,
        Set<Long> descendantMemberOfOrgIds,
        Map<ScopeKey, String> orgRoleByScope) {

    /**
     * 防御的コピーは行わない（呼び出し元が不変 Map/Set を渡す前提）。
     * null は空コレクションへ正規化する。
     */
    public UserScopeRoleSnapshot {
        roleByScope = roleByScope != null ? roleByScope : Map.of();
        parentOrgByScope = parentOrgByScope != null ? parentOrgByScope : Map.of();
        orgMemberOf = orgMemberOf != null ? orgMemberOf : Set.of();
        suspendedOrgIds = suspendedOrgIds != null ? suspendedOrgIds : Set.of();
        descendantMemberOfOrgIds = descendantMemberOfOrgIds != null ? descendantMemberOfOrgIds : Set.of();
        orgRoleByScope = orgRoleByScope != null ? orgRoleByScope : Map.of();
    }

    /**
     * CMP-017b 以前の 6 引数呼び出しとの後方互換コンストラクタ。
     * {@code orgRoleByScope} を空マップで補完してカノニカルコンストラクタへ委譲する。
     */
    public UserScopeRoleSnapshot(
            boolean systemAdmin,
            Map<ScopeKey, String> roleByScope,
            Map<ScopeKey, Long> parentOrgByScope,
            Set<ScopeKey> orgMemberOf,
            Set<Long> suspendedOrgIds,
            Set<Long> descendantMemberOfOrgIds) {
        this(systemAdmin, roleByScope, parentOrgByScope, orgMemberOf, suspendedOrgIds,
                descendantMemberOfOrgIds, Map.of());
    }

    /**
     * フェーズ M2 以前の 5 引数呼び出しとの後方互換コンストラクタ。
     * {@code descendantMemberOfOrgIds} を空集合で補完してカノニカルコンストラクタへ委譲する。
     *
     * <p>{@code ORGANIZATION_AND_DESCENDANTS} を扱わない既存 Resolver テスト・呼び出し元は
     * 本コンストラクタ経由で従来どおり 5 引数で生成でき、挙動も従来と完全一致する。</p>
     */
    public UserScopeRoleSnapshot(
            boolean systemAdmin,
            Map<ScopeKey, String> roleByScope,
            Map<ScopeKey, Long> parentOrgByScope,
            Set<ScopeKey> orgMemberOf,
            Set<Long> suspendedOrgIds) {
        this(systemAdmin, roleByScope, parentOrgByScope, orgMemberOf, suspendedOrgIds,
                Set.of(), Map.of());
    }

    /**
     * 匿名ユーザー（未ログイン or userId=null）用の空スナップショット。
     */
    public static UserScopeRoleSnapshot empty() {
        return new UserScopeRoleSnapshot(false, Map.of(), Map.of(), Set.of(), Set.of(), Set.of(), Map.of());
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
        return new UserScopeRoleSnapshot(true, Map.of(), Map.of(), Set.of(), Set.of(), Set.of(), Map.of());
    }

    public boolean isSystemAdmin() {
        return systemAdmin;
    }

    /**
     * スコープへの直接メンバーシップ（または SystemAdmin）を持つかを返す。
     *
     * <p>JOBBER 等の並行ロール（{@link RolePriority} マップ未登録）は、
     * {@code user_roles} 行が存在しても通常のメンバーとは区別する。
     * F13.1 §2.9 の仕様により、並行ロール保有者は MEMBERS_ONLY コンテンツを閲覧できない。</p>
     */
    public boolean isMemberOf(ScopeKey scope) {
        if (scope == null) {
            return false;
        }
        if (systemAdmin) {
            return true;
        }
        String role = roleByScope.get(scope);
        // JOBBER 等の並行ロール（RolePriority マップ未登録）は不可視
        return role != null && RolePriority.isRegistered(role);
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
     * 当該スコープの「親 ORG」において、要求ロール以上の<strong>直接所属ロール</strong>を
     * 持つかを返す（CMP-017b）。SystemAdmin は常に true。
     *
     * <p>{@link #isMemberOfParentOrg(ScopeKey)} が「親 ORG に所属しているか」しか答えられないのに対し、
     * 本メソッドは親 ORG での役職の高さを閾値で評価する。設計書
     * {@code docs/features/F03.1_schedule_shared.md}「{@code min_view_role} の評価スコープ（親子関係）」が
     * 定める「{@code visibility='ORGANIZATION'} のときは親組織への直接所属ロールで評価する」を
     * 実現するための土台である。</p>
     *
     * <p>参照するのは {@link #orgRoleByScope}（親 ORG の直接所属ロール）であり、
     * {@link #roleByScope}（コンテンツ所有スコープの直接所属ロール）ではない。
     * 「親グループのロールは子グループへ継承しない」という設計書の規定に従い、両者は混ぜない。</p>
     *
     * @param scope    コンテンツ所有スコープ（TEAM を想定。ORGANIZATION スコープは自身が親として登録される）
     * @param required 必要ロール名
     * @return 親 ORG で {@code required} 以上のロールを持つなら {@code true}
     */
    public boolean hasParentOrgRoleOrAbove(ScopeKey scope, String required) {
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
        String role = orgRoleByScope.get(new ScopeKey("ORGANIZATION", parentOrg));
        return role != null && RolePriority.isAtLeast(role, required);
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

    /**
     * 当該 ORG スコープのコンテンツが「再帰的配下メンバー」として可視かを返す
     * （フェーズ M2 / {@link StandardVisibility#ORGANIZATION_AND_DESCENDANTS} の中核ロジック）。
     *
     * <p>{@link #isMemberOfParentOrg(ScopeKey)}（上向き 1 段）の<strong>下向き再帰の鏡像</strong>。
     * scope が ORGANIZATION スコープであり、かつ viewer がその ORG を根とした再帰的配下ツリー
     * （全子孫組織の直属 ∪ それら組織の ACTIVE 参加チームメンバー）に属するなら true。</p>
     *
     * <p><strong>所属軸であり SUPPORTER を含む</strong>（G7）。SystemAdmin は常に true。
     * 直接所属（{@link #orgMemberOf}）とは別フィールド {@link #descendantMemberOfOrgIds} を参照するため、
     * {@link #isMemberOf(ScopeKey)} / {@link #isMemberOfParentOrg(ScopeKey)} の挙動には影響しない。</p>
     */
    public boolean isDescendantMemberOf(ScopeKey scope) {
        if (systemAdmin) {
            return true;
        }
        if (scope == null || !"ORGANIZATION".equals(scope.scopeType())) {
            return false;
        }
        return descendantMemberOfOrgIds.contains(scope.scopeId());
    }

    /**
     * 当該 ORG スコープの ORG <strong>自身</strong>が削除済 / SUSPENDED 状態かを返す
     * （§11.6 連鎖ルールの「下向き再帰版」鏡像）。
     *
     * <p>{@link #isParentOrgInactive(ScopeKey)} は TEAM コンテンツの「親 ORG」を見るが、
     * {@link StandardVisibility#ORGANIZATION_AND_DESCENDANTS} は ORG スコープのコンテンツが対象であり、
     * 評価すべき非アクティブ判定は「当該 ORG 自身」である。{@code parentOrgByScope} は
     * ORGANIZATION スコープに対して自身の ORG ID を entry として持つ（{@code ScopeAncestorResolver}）ため、
     * 当該 ORG 自身が非アクティブ集合 {@code suspendedOrgIds} に含まれるかで判定する。</p>
     *
     * <p>scope が ORGANIZATION でない、または判定不能（マッピング無し）の場合は false。</p>
     */
    public boolean isOrgInactive(ScopeKey scope) {
        if (scope == null || !"ORGANIZATION".equals(scope.scopeType())) {
            return false;
        }
        return suspendedOrgIds.contains(scope.scopeId());
    }
}
