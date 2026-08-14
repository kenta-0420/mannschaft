package com.mannschaft.app.schedule;

import jakarta.persistence.EntityManager;

/**
 * F03.16 予定コメントスレッド — 契約テスト共通フィクスチャ。
 *
 * <p>設計書: {@code docs/features/F03.16_schedule_comment_thread.md} §9。</p>
 *
 * <h2>所属の投入方針（重要）</h2>
 * <p>MEMBER / SUPPORTER の所属は <b>{@code memberships} を正</b>として投入する
 * （{@code V60.010} で {@code user_roles} から移行済であり、本番に MEMBER/SUPPORTER の
 * {@code user_roles} 行は存在しえない）。本番で成立しえない行をフィクスチャで張ると、
 * 死んだ機能が永久に緑になる。ADMIN / DEPUTY_ADMIN / SYSTEM_ADMIN は権限ロールであり
 * {@code memberships.role_kind} に値を持たないため、そちらのみ {@code user_roles} を用いる。</p>
 *
 * <h2>test プロファイルの前提</h2>
 * <p>{@code application-test.yml} は {@code ddl-auto: create} かつ {@code flyway.enabled: false} である。
 * スキーマは Entity 由来で生成され、<b>Flyway のシード（roles / permissions / role_permissions）は投入されない</b>。
 * 権限テーブルを引く判定（{@code DELETE_OTHERS_CONTENT} 等）を検証するテストは、
 * 必要な行を各テストクラスが明示的に投入すること。</p>
 */
final class ScheduleCommentTestFixtures {

    private ScheduleCommentTestFixtures() {
        // util
    }

    /** ACTIVE な users 行を 1 件作成し id を返す。 */
    static Long insertUser(EntityManager em, String email, String displayName) {
        em.createNativeQuery(
                        "INSERT INTO users ("
                                + "email, last_name, first_name, display_name, status, "
                                + "is_searchable, handle_searchable, contact_approval_required, "
                                + "online_visibility, dm_receive_from, encryption_key_version, "
                                + "locale, timezone, reporting_restricted, follow_list_visibility, "
                                + "care_notification_enabled, offline_only, "
                                + "created_at, updated_at) "
                                + "VALUES (:email, 'F0316', 'テスト', :dn, 'ACTIVE', "
                                + "1, 1, 1, "
                                + "'NOBODY', 'ANYONE', 1, "
                                + "'ja', 'Asia/Tokyo', 0, 'PUBLIC', "
                                + "1, 0, "
                                + "NOW(), NOW())")
                .setParameter("email", email)
                .setParameter("dn", displayName)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM users WHERE email = :email")
                .setParameter("email", email)
                .getSingleResult()).longValue();
    }

    /** teams 行を 1 件作成し id を返す。 */
    static Long insertTeam(EntityManager em, String name, String slug) {
        em.createNativeQuery(
                        "INSERT INTO teams (name, visibility, supporter_enabled, version, member_count, slug, "
                                + "created_at, updated_at) "
                                + "VALUES (:name, 'PUBLIC', 1, 0, 0, :slug, NOW(), NOW())")
                .setParameter("name", name)
                .setParameter("slug", slug)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM teams WHERE slug = :slug")
                .setParameter("slug", slug)
                .getSingleResult()).longValue();
    }

    /** organizations 行を 1 件作成し id を返す。 */
    static Long insertOrganization(EntityManager em, String name, String slug) {
        em.createNativeQuery(
                        "INSERT INTO organizations (name, org_type, visibility, hierarchy_visibility, "
                                + "supporter_enabled, version, slug, created_at, updated_at) "
                                + "VALUES (:name, 'OTHER', 'PUBLIC', 'NONE', 1, 0, :slug, NOW(), NOW())")
                .setParameter("name", name)
                .setParameter("slug", slug)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM organizations WHERE slug = :slug")
                .setParameter("slug", slug)
                .getSingleResult()).longValue();
    }

    /** ORGANIZATION_WIDE の親組織解決が成立するよう ACTIVE な team_org_memberships を張る。 */
    static void linkTeamToOrganization(EntityManager em, Long teamId, Long organizationId) {
        em.createNativeQuery(
                        "INSERT INTO team_org_memberships ("
                                + "team_id, organization_id, status, invited_at, created_at) "
                                + "VALUES (:tid, :oid, 'ACTIVE', NOW(), NOW())")
                .setParameter("tid", teamId)
                .setParameter("oid", organizationId)
                .executeUpdate();
    }

    /** schedule_comments の実行数を数える（API 応答ではなく DB の実体で検証するため）。 */
    static long countComments(EntityManager em, Long scheduleId) {
        return ((Number) em.createNativeQuery(
                        "SELECT COUNT(*) FROM schedule_comments WHERE schedule_id = :sid")
                .setParameter("sid", scheduleId)
                .getSingleResult()).longValue();
    }

    /** 指定ユーザー宛の通知件数を数える（通知が「届かないこと」の検証に使う）。 */
    static long countNotifications(EntityManager em, Long userId) {
        return ((Number) em.createNativeQuery(
                        "SELECT COUNT(*) FROM notifications WHERE user_id = :uid")
                .setParameter("uid", userId)
                .getSingleResult()).longValue();
    }

    /** 指定ユーザー宛・指定種別の通知件数を数える。 */
    static long countNotifications(EntityManager em, Long userId, String notificationType) {
        return ((Number) em.createNativeQuery(
                        "SELECT COUNT(*) FROM notifications "
                                + "WHERE user_id = :uid AND notification_type = :type")
                .setParameter("uid", userId)
                .setParameter("type", notificationType)
                .getSingleResult()).longValue();
    }
}
