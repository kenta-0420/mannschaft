package com.mannschaft.app.team.affiliation;

import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.support.test.MembershipTestHelper;

import java.nio.ByteBuffer;
import java.util.UUID;

/**
 * F01.2.1 部隊 2-C（組織からの招待・承諾・辞退・取消、両側の制限一覧と解除）の統合テストが共有するフィクスチャ。
 *
 * <p>2-B1 の {@link TeamAffiliationItSupport} の人物構成に、組織側の人物（XD＝組織 DEPUTY_ADMIN、
 * XM＝組織 MEMBER、YA＝別組織の ADMIN、SYS＝SYSTEM_ADMIN）と、非公開チーム・制限行の前提作りを足す。
 * 行は native SQL で作る（検証対象の API を前提作りに使わない）。</p>
 */
abstract class TeamOrgInviteItSupport extends TeamAffiliationItSupport {

    /** 組織の DEPUTY_ADMIN（memberships と user_roles の二重 seed）。 */
    protected void makeOrgDeputy(long userId, long orgId) {
        MembershipTestHelper.insertMembership(em, userId, ScopeType.ORGANIZATION, orgId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, userId, "DEPUTY_ADMIN", null, orgId);
    }

    /** 組織の MEMBER（所属だけ。ロール行は張らない）。 */
    protected void makeOrgMember(long userId, long orgId) {
        MembershipTestHelper.insertMembership(em, userId, ScopeType.ORGANIZATION, orgId, RoleKind.MEMBER);
    }

    /** プラットフォームの SYSTEM_ADMIN。 */
    protected void makeSystemAdmin(long userId) {
        MembershipTestHelper.insertUserRole(em, userId, "SYSTEM_ADMIN", null, null);
    }

    /** チームを非公開（MEMBERS_AND_ABOVE。所属していない人からは見えない）にする。 */
    protected void makeTeamPrivate(long teamId) {
        em.createNativeQuery("UPDATE teams SET visibility = 'MEMBERS_AND_ABOVE' WHERE id = :id")
                .setParameter("id", teamId).executeUpdate();
    }

    /** チームグループを論理削除する（4-A のリスナーは走らせず、group_id は残したままにする）。 */
    protected void softDeleteGroup(UUID groupId) {
        em.createNativeQuery("UPDATE org_team_groups SET deleted_at = UTC_TIMESTAMP() WHERE id = :id")
                .setParameter("id", groupId).executeUpdate();
    }

    /** 制限行の ID を (組織, チーム, 向き) で引く。無ければ null。 */
    protected UUID restrictionIdOf(long orgId, long teamId, String direction) {
        var rows = em.createNativeQuery(
                        "SELECT id FROM team_org_affiliation_restrictions "
                                + "WHERE organization_id = :orgId AND team_id = :teamId AND direction = :direction")
                .setParameter("orgId", orgId)
                .setParameter("teamId", teamId)
                .setParameter("direction", direction)
                .getResultList();
        if (rows.isEmpty()) {
            return null;
        }
        ByteBuffer buffer = ByteBuffer.wrap((byte[]) rows.get(0));
        return new UUID(buffer.getLong(), buffer.getLong());
    }

    protected long countRestrictions(long orgId, long teamId) {
        return ((Number) em.createNativeQuery(
                        "SELECT COUNT(*) FROM team_org_affiliation_restrictions "
                                + "WHERE organization_id = :orgId AND team_id = :teamId")
                .setParameter("orgId", orgId)
                .setParameter("teamId", teamId)
                .getSingleResult()).longValue();
    }

    /** 加盟行の (status, direction, group_id, message, responded_by) を引く。 */
    protected Object[] membershipRow(long membershipId) {
        return (Object[]) em.createNativeQuery(
                        "SELECT status, direction, group_id, message, responded_by FROM team_org_memberships "
                                + "WHERE id = :id")
                .setParameter("id", membershipId)
                .getSingleResult();
    }

    protected static UUID uuidOf(Object raw) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof UUID uuid) {
            return uuid;
        }
        ByteBuffer buffer = ByteBuffer.wrap((byte[]) raw);
        return new UUID(buffer.getLong(), buffer.getLong());
    }
}
