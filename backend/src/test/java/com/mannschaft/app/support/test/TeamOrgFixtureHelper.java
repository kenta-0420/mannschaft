package com.mannschaft.app.support.test;

import jakarta.persistence.EntityManager;

import java.time.LocalDateTime;

/**
 * 複数組織への同時加盟（F01.2.1）の契約テスト用に、組織・チーム・加盟行を native INSERT で作るヘルパー。
 *
 * <p>team_org_memberships の書き込み API を経由せず行を直接作るため、{@code responded_at} を任意に指定できる
 * （代表親組織 §9.3 の「最初に成立した加盟」を検証するため）。Repository・Service はモックしない。</p>
 */
public final class TeamOrgFixtureHelper {

    private TeamOrgFixtureHelper() {
        // util
    }

    public static Long insertOrganization(EntityManager em, String name, String slug) {
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

    public static Long insertTeam(EntityManager em, String name, String slug) {
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

    /** チームと組織の加盟行を作る（{@code responded_at} = 加盟の成立時刻。PENDING にもわざと入れる）。 */
    public static void insertTeamOrgMembership(EntityManager em, Long teamId, Long orgId, String status,
                                               LocalDateTime respondedAt) {
        em.createNativeQuery(
                        "INSERT INTO team_org_memberships ("
                                + "team_id, organization_id, status, invited_at, responded_at, created_at) "
                                + "VALUES (:tid, :oid, :st, :invited, :responded, NOW())")
                .setParameter("tid", teamId)
                .setParameter("oid", orgId)
                .setParameter("st", status)
                .setParameter("invited", respondedAt.minusDays(1))
                .setParameter("responded", respondedAt)
                .executeUpdate();
    }

    /** 組織スコープの行事カテゴリを作る。 */
    public static Long insertOrgEventCategory(EntityManager em, Long orgId, String name, int sortOrder) {
        em.createNativeQuery(
                        "INSERT INTO schedule_event_categories (organization_id, name, color, is_day_off_category, "
                                + "sort_order, created_at, updated_at) "
                                + "VALUES (:oid, :name, '#3B82F6', 0, :so, NOW(), NOW())")
                .setParameter("oid", orgId)
                .setParameter("name", name)
                .setParameter("so", sortOrder)
                .executeUpdate();
        return ((Number) em.createNativeQuery(
                        "SELECT id FROM schedule_event_categories WHERE organization_id = :oid AND name = :name")
                .setParameter("oid", orgId)
                .setParameter("name", name)
                .getSingleResult()).longValue();
    }

    /** チームスコープの行事カテゴリを作る。 */
    public static Long insertTeamEventCategory(EntityManager em, Long teamId, String name, int sortOrder) {
        em.createNativeQuery(
                        "INSERT INTO schedule_event_categories (team_id, name, color, is_day_off_category, "
                                + "sort_order, created_at, updated_at) "
                                + "VALUES (:tid, :name, '#10B981', 0, :so, NOW(), NOW())")
                .setParameter("tid", teamId)
                .setParameter("name", name)
                .setParameter("so", sortOrder)
                .executeUpdate();
        return ((Number) em.createNativeQuery(
                        "SELECT id FROM schedule_event_categories WHERE team_id = :tid AND name = :name")
                .setParameter("tid", teamId)
                .setParameter("name", name)
                .getSingleResult()).longValue();
    }
}
