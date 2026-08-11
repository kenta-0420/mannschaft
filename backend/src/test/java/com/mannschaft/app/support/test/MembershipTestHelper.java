package com.mannschaft.app.support.test;

import com.mannschaft.app.common.visibility.RolePriority;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;

/**
 * 統合テスト用 memberships / user_roles INSERT ヘルパー。
 *
 * <p>F09.13 Phase 2-α-1 で Disabled テスト 2 件（{@code exportSinglePdf/Xlsx}）を再有効化する
 * にあたり、各統合テストで散発的にコピペされていた INSERT パターンを集約した。</p>
 *
 * <h3>2 系統の所属テーブル</h3>
 * <ul>
 *   <li>{@code memberships} — F00.5 で導入された「メンバーシップそのもの」テーブル
 *       （誰が・どのスコープに・いつ入退会したか）。{@link RoleKind} は MEMBER/SUPPORTER のみ。</li>
 *   <li>{@code user_roles} — 既存の権限ロール割当テーブル。
 *       {@code roles} を JOIN して role_name (ADMIN/DEPUTY_ADMIN/MEMBER/SUPPORTER/SYSTEM_ADMIN) を解決する。
 *       {@link com.mannschaft.app.common.visibility.MembershipBatchQueryService} の判定はこちら。</li>
 * </ul>
 *
 * <p>マスキング判定（{@code PropertyWorkPackageMaskingService}）や F00 共通可視性基盤は
 * <strong>user_roles 側の role_name</strong> を見ている。よって ADMIN 相当の権限を要する
 * 統合テストでは {@link #insertUserRole} で {@code roles.id} の参照を作る必要がある。
 * {@link #insertMembership} だけでは MEMBER/SUPPORTER の所属は作れるが、ADMIN 判定は通らない。</p>
 *
 * <p>本ヘルパーは {@code @PersistenceContext EntityManager} を引数で受け取る static method 群
 * とし、テストクラス側からは {@code MembershipTestHelper.insertMembership(em, ...)} の形で呼ぶ。</p>
 */
public final class MembershipTestHelper {

    private MembershipTestHelper() {
        // util
    }

    /**
     * memberships へアクティブメンバーシップを 1 行 INSERT する。
     *
     * <p>{@code joined_at = NOW()}, {@code left_at = NULL}（アクティブ）, {@code created_at/updated_at = NOW()}
     * を自動で埋める。{@code invited_by} は NULL 固定（自己登録扱い）。</p>
     *
     * @param em         EntityManager（テストクラスの {@code @PersistenceContext} を渡す）
     * @param userId     所属ユーザー ID
     * @param scopeType  ORGANIZATION / TEAM
     * @param scopeId    teams.id または organizations.id
     * @param roleKind   MEMBER または SUPPORTER
     */
    public static void insertMembership(EntityManager em, Long userId,
                                        ScopeType scopeType, Long scopeId, RoleKind roleKind) {
        em.createNativeQuery(
                "INSERT INTO memberships ("
                        + "user_id, scope_type, scope_id, role_kind, "
                        + "joined_at, left_at, leave_reason, invited_by, "
                        + "created_at, updated_at) "
                        + "VALUES (:uid, :st, :sid, :rk, "
                        + "NOW(), NULL, NULL, NULL, "
                        + "NOW(), NOW())")
                .setParameter("uid", userId)
                .setParameter("st", scopeType.name())
                .setParameter("sid", scopeId)
                .setParameter("rk", roleKind.name())
                .executeUpdate();
    }

    /**
     * user_roles へ権限ロール割当を 1 行 INSERT する。
     *
     * <p>{@code roles.name = roleName} の id を解決し、それを {@code role_id} に投入する。
     * roles テーブルには Flyway V2.014 で SYSTEM_ADMIN/ADMIN/DEPUTY_ADMIN/MEMBER/SUPPORTER/GUEST が
     * seed 投入済の前提。</p>
     *
     * <p>SYSTEM_ADMIN を付与する場合は {@code teamId == null && organizationId == null} で呼ぶ
     * （プラットフォームレベル割当）。チーム ADMIN なら {@code teamId != null && organizationId == null}、
     * 組織 ADMIN なら {@code teamId == null && organizationId != null}。</p>
     *
     * @param em             EntityManager
     * @param userId         対象ユーザー
     * @param roleName       roles.name（"ADMIN" / "DEPUTY_ADMIN" / "MEMBER" / "SUPPORTER" / "SYSTEM_ADMIN"）
     * @param teamId         所属チーム（不要なら null）
     * @param organizationId 所属組織（不要なら null）
     */
    public static void insertUserRole(EntityManager em, Long userId, String roleName,
                                      Long teamId, Long organizationId) {
        Long roleId = resolveRoleIdByName(em, roleName);
        em.createNativeQuery(
                "INSERT INTO user_roles ("
                        + "user_id, role_id, team_id, organization_id, "
                        + "created_at, updated_at) "
                        + "VALUES (:uid, :rid, :tid, :oid, NOW(), NOW())")
                .setParameter("uid", userId)
                .setParameter("rid", roleId)
                .setParameter("tid", teamId)
                .setParameter("oid", organizationId)
                .executeUpdate();
    }

    /**
     * roles.name から id を 1 SQL で解決する。
     *
     * <p>未投入ならオンデマンドで INSERT してから再取得する。priority は正準表
     * {@link RolePriority}（{@code V2.014__seed_roles.sql} の seed と一致）から採る。
     * かつて全ロールに固定値 {@code priority = 99} を割り当てていたため、
     * {@code AccessControlService.hasRoleOrAbove(...)} の優先度比較が全ロール同点になり、
     * 下位ロールが上位ロールの閾値を素通りしていた（例: SUPPORTER が MEMBER 相当と判定される）。
     * 値を写経すると再び drift するため、本番と同じ定数表を直接参照する。</p>
     */
    private static Long resolveRoleIdByName(EntityManager em, String roleName) {
        try {
            Object result = em.createNativeQuery("SELECT id FROM roles WHERE name = :name")
                    .setParameter("name", roleName)
                    .getSingleResult();
            return ((Number) result).longValue();
        } catch (NoResultException e) {
            // 統合テスト環境では application-test.yml で flyway.enabled=false のため
            // V2.014__seed_roles.sql の固定 role が投入されない。
            int priority = RolePriority.priority(roleName);
            if (priority == Integer.MAX_VALUE) {
                throw new IllegalStateException(
                        "未知の role 名です（RolePriority に定義がありません）: " + roleName);
            }
            em.createNativeQuery(
                    "INSERT INTO roles (name, display_name, priority, is_system, "
                            + "created_at, updated_at) "
                            + "VALUES (:name, :name, :priority, 0, NOW(), NOW())")
                    .setParameter("name", roleName)
                    .setParameter("priority", priority)
                    .executeUpdate();
            Object result = em.createNativeQuery("SELECT id FROM roles WHERE name = :name")
                    .setParameter("name", roleName)
                    .getSingleResult();
            return ((Number) result).longValue();
        }
    }
}
