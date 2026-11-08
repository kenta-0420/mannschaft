package com.mannschaft.app.support.test;

import jakarta.persistence.EntityManager;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * N+1 解消の試練（SQL 数固定テスト）で使う最小フィクスチャ投入ヘルパ（CMP-261103-1225 / #4270）。
 *
 * <p>ユーザー・チーム・組織・所属・ロールは各ドメインの業務 Service を通すと副作用（通知・監査等）が
 * 大きいため、既存 IT（{@code AdminOrPermissionInScopeIT}）と同じ列構成の native INSERT で投入する。
 * 各テストはクラス {@code @Transactional} でロールバックされる前提で、後始末は持たない。</p>
 */
public final class N1SeedSupport {

    private final EntityManager em;

    public N1SeedSupport(EntityManager em) {
        this.em = em;
    }

    /** ACTIVE ユーザーを作成して ID を返す。 */
    public Long user(String firstName) {
        String email = "n1-" + UUID.randomUUID().toString().substring(0, 12) + "@example.com";
        em.createNativeQuery(
                "INSERT INTO users (email, last_name, first_name, display_name, status, "
                        + "is_searchable, handle_searchable, contact_approval_required, "
                        + "online_visibility, dm_receive_from, encryption_key_version, "
                        + "locale, timezone, reporting_restricted, follow_list_visibility, "
                        + "care_notification_enabled, offline_only, created_at, updated_at) "
                        + "VALUES (:email, '試験', :fn, :dn, 'ACTIVE', 1, 1, 1, "
                        + "'NOBODY', 'ANYONE', 1, 'ja', 'Asia/Tokyo', 0, 'PUBLIC', 1, 0, NOW(), NOW())")
                .setParameter("email", email)
                .setParameter("fn", firstName)
                .setParameter("dn", "試験 " + firstName)
                .executeUpdate();
        return lastId();
    }

    /** ユーザーを論理削除（退会扱い）にする。 */
    public void softDeleteUser(Long userId) {
        em.createNativeQuery("UPDATE users SET deleted_at = NOW() WHERE id = :id")
                .setParameter("id", userId).executeUpdate();
    }

    /** チームを作成して ID を返す。 */
    public Long team(String name, String iconUrl) {
        em.createNativeQuery(
                "INSERT INTO teams (name, icon_url, visibility, supporter_enabled, version, member_count, slug, "
                        + "created_at, updated_at) "
                        + "VALUES (:name, :icon, 'PUBLIC', 1, 0, 0, CONCAT('s-', LEFT(REPLACE(UUID(),'-',''),8)), "
                        + "NOW(), NOW())")
                .setParameter("name", name)
                .setParameter("icon", iconUrl)
                .executeUpdate();
        return lastId();
    }

    /** チームを論理削除する。 */
    public void softDeleteTeam(Long teamId) {
        em.createNativeQuery("UPDATE teams SET deleted_at = NOW() WHERE id = :id")
                .setParameter("id", teamId).executeUpdate();
    }

    /** 組織を作成して ID を返す。 */
    public Long organization(String name, String iconUrl) {
        em.createNativeQuery(
                "INSERT INTO organizations (name, icon_url, org_type, visibility, hierarchy_visibility, "
                        + "supporter_enabled, version, slug, created_at, updated_at) "
                        + "VALUES (:name, :icon, 'OTHER', 'PUBLIC', 'NONE', 1, 0, "
                        + "CONCAT('s-', LEFT(REPLACE(UUID(),'-',''),8)), NOW(), NOW())")
                .setParameter("name", name)
                .setParameter("icon", iconUrl)
                .executeUpdate();
        return lastId();
    }

    /** チームにロール付き在籍（user_roles + memberships）を作る。 */
    public void joinTeam(Long userId, Long teamId, String roleName) {
        role(userId, teamId, null, roleName);
        membership(userId, "TEAM", teamId);
    }

    /** 組織にロール付き在籍（user_roles + memberships）を作る。 */
    public void joinOrganization(Long userId, Long orgId, String roleName) {
        role(userId, null, orgId, roleName);
        membership(userId, "ORGANIZATION", orgId);
    }

    /**
     * ロール定義を（無ければ）作る。test プロファイルは ddl-auto=create で Flyway のシードが入らないため、
     * 既存 IT（{@code WebhookAuthzContractIT}）と同じく必要なロールを都度用意する。
     */
    public void ensureRole(String roleName) {
        long exists = ((Number) em.createNativeQuery("SELECT COUNT(*) FROM roles WHERE name = :name")
                .setParameter("name", roleName).getSingleResult()).longValue();
        if (exists > 0) {
            return;
        }
        em.createNativeQuery(
                "INSERT INTO roles (name, display_name, priority, is_system, created_at, updated_at) "
                        + "VALUES (:name, :name, 9, 0, NOW(), NOW())")
                .setParameter("name", roleName)
                .executeUpdate();
    }

    /** モジュール定義を（無ければ）作る。ddl-auto=create のため Flyway シードの代わりに用意する。 */
    public void ensureModule(String slug, String moduleType) {
        long exists = ((Number) em.createNativeQuery("SELECT COUNT(*) FROM module_definitions WHERE slug = :slug")
                .setParameter("slug", slug).getSingleResult()).longValue();
        if (exists > 0) {
            return;
        }
        em.createNativeQuery(
                "INSERT INTO module_definitions (name, slug, module_type, module_number, requires_paid_plan, "
                        + "trial_days, is_active, created_at, updated_at) "
                        + "VALUES (:slug, :slug, :type, 999, 0, 14, 1, NOW(), NOW())")
                .setParameter("slug", slug)
                .setParameter("type", moduleType)
                .executeUpdate();
    }

    private void role(Long userId, Long teamId, Long orgId, String roleName) {
        ensureRole(roleName);
        em.createNativeQuery(
                "INSERT INTO user_roles (user_id, role_id, team_id, organization_id, created_at, updated_at) "
                        + "SELECT :uid, r.id, :tid, :oid, NOW(), NOW() FROM roles r WHERE r.name = :role")
                .setParameter("uid", userId)
                .setParameter("tid", teamId)
                .setParameter("oid", orgId)
                .setParameter("role", roleName)
                .executeUpdate();
    }

    private void membership(Long userId, String scopeType, Long scopeId) {
        em.createNativeQuery(
                "INSERT INTO memberships (user_id, scope_type, scope_id, role_kind, joined_at, created_at, updated_at) "
                        + "VALUES (:uid, :st, :sid, 'MEMBER', NOW(), NOW(), NOW())")
                .setParameter("uid", userId)
                .setParameter("st", scopeType)
                .setParameter("sid", scopeId)
                .executeUpdate();
    }

    /** 掲示板スレッドを作成し、updated_at / is_pinned を明示して ID を返す。 */
    public Long bulletinThread(String scopeType, Long scopeId, Long authorId, String title,
                               boolean pinned, LocalDateTime updatedAt) {
        em.createNativeQuery(
                "INSERT INTO bulletin_threads (scope_type, scope_id, author_id, title, body, priority, "
                        + "read_tracking_mode, is_pinned, is_locked, is_archived, reply_count, read_count, "
                        + "posted_as_subject_type, created_at, updated_at) "
                        + "VALUES (:st, :sid, :aid, :title, 'body', 'INFO', 'COUNT_ONLY', :pinned, 0, 0, 0, 0, "
                        + "'USER', :ts, :ts)")
                .setParameter("st", scopeType)
                .setParameter("sid", scopeId)
                .setParameter("aid", authorId)
                .setParameter("title", title)
                .setParameter("pinned", pinned)
                .setParameter("ts", updatedAt)
                .executeUpdate();
        return lastId();
    }

    /** スレッドを既読にする。 */
    public void readThread(Long threadId, Long userId) {
        em.createNativeQuery(
                "INSERT INTO bulletin_read_status (thread_id, user_id, read_at) VALUES (:t, :u, NOW())")
                .setParameter("t", threadId)
                .setParameter("u", userId)
                .executeUpdate();
    }

    /** 直前の INSERT の自動採番 ID。 */
    public Long lastId() {
        return ((Number) em.createNativeQuery("SELECT LAST_INSERT_ID()").getSingleResult()).longValue();
    }

    /** flush + clear して 1 次キャッシュを空にする（SQL 数の計測を公平にするため）。 */
    public void flushAndClear() {
        em.flush();
        em.clear();
    }

    /** 統計を有効化・初期化して返す。 */
    public Statistics stats() {
        Statistics s = em.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
        s.setStatisticsEnabled(true);
        s.clear();
        return s;
    }
}
