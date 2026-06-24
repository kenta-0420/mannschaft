package com.mannschaft.app.payment;

import com.mannschaft.app.payment.dto.CreateManualPaymentRequest;
import com.mannschaft.app.payment.entity.MemberPaymentEntity;
import com.mannschaft.app.payment.repository.MemberPaymentRepository;
import com.mannschaft.app.payment.service.MemberPaymentService;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * F08.9 会費受益者制限「会員のみ（memberOnly）」設定の難所＝受益者合成ロジック結合テスト
 * （実 MySQL ＋ 実 {@link com.mannschaft.app.common.AccessControlService}）。
 *
 * <p><b>目的:</b> 設定 ON（既定）/OFF で受益者判定が切り替わることを、純 Mockito では裏取りできない
 * 実メンバーシップ・実 priority 比較（{@code hasRoleOrAbove}）・実
 * {@code isInOrgDistributionAudience} で検証する。</p>
 *
 * <ul>
 *   <li><b>AC-S5</b>: memberOnly=true（既定・設定行なし）で TEAM 会費の純 SUPPORTER 受益者 → USER_NOT_MEMBER。</li>
 *   <li><b>AC-S6</b>: memberOnly=true で TEAM MEMBER 正常・組織直接 MEMBER 正常・組織配下チーム MEMBER 許容・
 *       組織配下の純 SUPPORTER 除外。</li>
 *   <li><b>AC-S7</b>: memberOnly=false で TEAM 会費の SUPPORTER 受益者も正常（応援者も受益者可）。</li>
 * </ul>
 *
 * <p>seed は {@link MemberPaymentAuthzIntegrationTest} を踏襲（priority: ADMIN(2) &lt; MEMBER(4) &lt; SUPPORTER(5)）。
 * 設定行は {@code payment_beneficiary_settings} に直 INSERT する（未 INSERT＝既定 true）。</p>
 */
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("会費受益者『会員のみ』設定 難所 結合テスト（実 MySQL・実 AccessControlService）")
class PaymentBeneficiaryMemberOnlyIntegrationTest extends AbstractMySqlIntegrationTest {

    @Autowired private MemberPaymentService memberPaymentService;
    @Autowired private MemberPaymentRepository memberPaymentRepository;

    @PersistenceContext private EntityManager em;

    private Long teamId;
    private Long orgId;
    private Long descendantTeamId;

    private Long adminUserId;          // user_roles: TEAM/ORG ADMIN（払い手権原）
    private Long teamMemberUserId;     // memberships: TEAM MEMBER
    private Long teamSupporterUserId;  // memberships: TEAM SUPPORTER（対象チーム直接）
    private Long orgMemberUserId;      // memberships: ORGANIZATION MEMBER（組織直接）
    private Long descMemberUserId;     // 組織配下チームの MEMBER（直接 ORG 所属なし）
    private Long descSupporterUserId;  // 組織配下チームの純 SUPPORTER

    private Long teamItemId;           // TEAM ANNUAL_FEE
    private Long orgItemId;            // ORGANIZATION ANNUAL_FEE

    @BeforeEach
    void setUp() {
        insertRole("ADMIN", "管理者", 2, true);
        insertRole("MEMBER", "メンバー", 4, false);
        insertRole("SUPPORTER", "サポーター", 5, false);
        em.flush();
        Long adminRoleId = roleId("ADMIN");
        Long memberRoleId = roleId("MEMBER");
        Long supporterRoleId = roleId("SUPPORTER");

        teamId = insertTeam("受益者制限 チーム");
        orgId = insertOrganization("受益者制限 組織");
        descendantTeamId = insertTeam("受益者制限 配下チーム");
        insertTeamOrgMembership(descendantTeamId, orgId);

        adminUserId = insertUser("bmo.admin@example.com", "管理", "者");
        teamMemberUserId = insertUser("bmo.teammember@example.com", "会員", "太郎");
        teamSupporterUserId = insertUser("bmo.teamsupporter@example.com", "支援", "太郎");
        orgMemberUserId = insertUser("bmo.orgmember@example.com", "組織会員", "花子");
        descMemberUserId = insertUser("bmo.descmember@example.com", "配下会員", "次郎");
        descSupporterUserId = insertUser("bmo.descsupporter@example.com", "配下支援", "三郎");

        // 払い手 admin: TEAM/ORG ADMIN
        insertUserRole(adminUserId, adminRoleId, teamId, null);
        insertUserRole(adminUserId, adminRoleId, null, orgId);

        // 受益者の所属（memberships）と所属の足場（user_roles）
        // hasRoleOrAbove は resolveEffectiveRole（user_roles + memberships.role_kind 統合）で判定するため、
        // memberships に role_kind を持たせれば MEMBER/SUPPORTER の priority が解決される。
        insertMembership(teamMemberUserId, "TEAM", teamId, "MEMBER");
        insertMembership(teamSupporterUserId, "TEAM", teamId, "SUPPORTER");
        insertMembership(orgMemberUserId, "ORGANIZATION", orgId, "MEMBER");
        // 組織直接 MEMBER は user_roles にも ORG MEMBER の足場を置く（isInOrgDistributionAudience の母集団判定用）。
        insertUserRole(orgMemberUserId, memberRoleId, null, orgId);

        // 配下チームの MEMBER / 純 SUPPORTER（直接 ORG 所属なし）
        insertMembership(descMemberUserId, "TEAM", descendantTeamId, "MEMBER");
        insertUserRole(descMemberUserId, memberRoleId, descendantTeamId, null);
        insertMembership(descSupporterUserId, "TEAM", descendantTeamId, "SUPPORTER");
        insertUserRole(descSupporterUserId, supporterRoleId, descendantTeamId, null);

        teamItemId = insertPaymentItem("年会費（チーム）", teamId, null);
        orgItemId = insertPaymentItem("年会費（組織）", null, orgId);

        em.flush();
        em.clear();
    }

    // =========================================================================
    // AC-S5: memberOnly=true（既定）で TEAM 純 SUPPORTER 受益者は拒否
    // =========================================================================

    @Test
    @DisplayName("[AC-S5] memberOnly=true（設定行なし＝既定）で TEAM 会費の純 SUPPORTER 受益者 → USER_NOT_MEMBER")
    void AC_S5_既定ONでTEAM純SUPPORTERは拒否() {
        // 設定行を作らない＝既定 true（会員のみ）。SUPPORTER(priority5) は hasRoleOrAbove("MEMBER") で false。
        assertThatThrownBy(() ->
                memberPaymentService.createManualPayment(teamItemId, adminUserId, manualReq(teamSupporterUserId)))
                .isInstanceOf(com.mannschaft.app.common.BusinessException.class)
                .extracting(e -> ((com.mannschaft.app.common.BusinessException) e).getErrorCode())
                .isEqualTo(PaymentErrorCode.USER_NOT_MEMBER);

        assertThat(memberPaymentRepository.findByPaymentItemId(teamItemId)).isEmpty();
    }

    // =========================================================================
    // AC-S6: memberOnly=true で MEMBER 許容・配下 MEMBER 許容・配下純 SUPPORTER 除外
    // =========================================================================

    @Test
    @DisplayName("[AC-S6] memberOnly=true で TEAM MEMBER 受益者は正常作成")
    void AC_S6_既定ONでTEAM_MEMBERは正常() {
        assertThatCode(() ->
                memberPaymentService.createManualPayment(teamItemId, adminUserId, manualReq(teamMemberUserId)))
                .doesNotThrowAnyException();

        List<MemberPaymentEntity> saved = memberPaymentRepository.findByPaymentItemId(teamItemId);
        assertThat(saved).hasSize(1);
        assertThat(saved.get(0).getUserId()).isEqualTo(teamMemberUserId);
    }

    @Test
    @DisplayName("[AC-S6] memberOnly=true で 組織直接 MEMBER 受益者は正常作成（ORGANIZATION）")
    void AC_S6_既定ONで組織直接MEMBERは正常() {
        assertThatCode(() ->
                memberPaymentService.createManualPayment(orgItemId, adminUserId, manualReq(orgMemberUserId)))
                .doesNotThrowAnyException();

        assertThat(memberPaymentRepository.findByPaymentItemId(orgItemId)).hasSize(1);
    }

    @Test
    @DisplayName("[AC-S6] memberOnly=true で 組織配下チームの MEMBER 受益者は許容（isInOrgDistributionAudience）")
    void AC_S6_既定ONで配下チームMEMBERは許容() {
        assertThatCode(() ->
                memberPaymentService.createManualPayment(orgItemId, adminUserId, manualReq(descMemberUserId)))
                .doesNotThrowAnyException();

        List<MemberPaymentEntity> saved = memberPaymentRepository.findByPaymentItemId(orgItemId);
        assertThat(saved).hasSize(1);
        assertThat(saved.get(0).getUserId()).isEqualTo(descMemberUserId);
    }

    @Test
    @DisplayName("[AC-S6] memberOnly=true で 組織配下チームの純 SUPPORTER 受益者は除外 → USER_NOT_MEMBER")
    void AC_S6_既定ONで配下純SUPPORTERは除外() {
        assertThatThrownBy(() ->
                memberPaymentService.createManualPayment(orgItemId, adminUserId, manualReq(descSupporterUserId)))
                .isInstanceOf(com.mannschaft.app.common.BusinessException.class)
                .extracting(e -> ((com.mannschaft.app.common.BusinessException) e).getErrorCode())
                .isEqualTo(PaymentErrorCode.USER_NOT_MEMBER);

        assertThat(memberPaymentRepository.findByPaymentItemId(orgItemId)).isEmpty();
    }

    // =========================================================================
    // AC-S7: memberOnly=false で TEAM SUPPORTER 受益者も可
    // =========================================================================

    @Test
    @DisplayName("[AC-S7] memberOnly=false で TEAM 会費の SUPPORTER 受益者も正常作成（応援者も可）")
    void AC_S7_設定OFFでTEAM_SUPPORTERは正常() {
        // 設定行を memberOnly=false で作る → isMemberOrDescendant 経路（TEAM は SUPPORTER 許容）。
        insertBeneficiarySetting(teamId, null, false);
        em.flush();
        em.clear();

        assertThatCode(() ->
                memberPaymentService.createManualPayment(teamItemId, adminUserId, manualReq(teamSupporterUserId)))
                .doesNotThrowAnyException();

        List<MemberPaymentEntity> saved = memberPaymentRepository.findByPaymentItemId(teamItemId);
        assertThat(saved).hasSize(1);
        assertThat(saved.get(0).getUserId()).isEqualTo(teamSupporterUserId);
    }

    // =========================================================================
    // リクエスト DTO
    // =========================================================================

    private CreateManualPaymentRequest manualReq(Long userId) {
        return new CreateManualPaymentRequest(
                userId, new BigDecimal("1000.00"), LocalDateTime.now(),
                null, null, null, null);
    }

    // =========================================================================
    // seed ヘルパー（MemberPaymentAuthzIntegrationTest 踏襲）
    // =========================================================================

    private void insertRole(String name, String displayName, int priority, boolean isSystem) {
        em.createNativeQuery(
                "INSERT INTO roles (name, display_name, priority, is_system, created_at, updated_at) "
                        + "VALUES (:name, :dn, :pri, :sys, NOW(), NOW())")
                .setParameter("name", name).setParameter("dn", displayName)
                .setParameter("pri", priority).setParameter("sys", isSystem ? 1 : 0)
                .executeUpdate();
    }

    private Long roleId(String name) {
        return ((Number) em.createNativeQuery("SELECT id FROM roles WHERE name = :name")
                .setParameter("name", name).getSingleResult()).longValue();
    }

    private Long insertUser(String email, String lastName, String firstName) {
        em.createNativeQuery(
                "INSERT INTO users (email, last_name, first_name, display_name, status, "
                        + "is_searchable, handle_searchable, contact_approval_required, "
                        + "online_visibility, dm_receive_from, encryption_key_version, "
                        + "locale, timezone, reporting_restricted, follow_list_visibility, "
                        + "care_notification_enabled, offline_only, created_at, updated_at) "
                        + "VALUES (:email, :ln, :fn, :dn, 'ACTIVE', 1, 1, 1, "
                        + "'NOBODY', 'ANYONE', 1, 'ja', 'Asia/Tokyo', 0, 'PUBLIC', 1, 0, NOW(), NOW())")
                .setParameter("email", email).setParameter("ln", lastName)
                .setParameter("fn", firstName).setParameter("dn", lastName + " " + firstName)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM users WHERE email = :email")
                .setParameter("email", email).getSingleResult()).longValue();
    }

    private Long insertOrganization(String name) {
        em.createNativeQuery(
                "INSERT INTO organizations (name, org_type, visibility, hierarchy_visibility, "
                        + "supporter_enabled, version, slug, created_at, updated_at) "
                        + "VALUES (:name, 'OTHER', 'PUBLIC', 'NONE', 1, 0, "
                        + "CONCAT('s-', LEFT(REPLACE(UUID(),'-',''),8)), NOW(), NOW())")
                .setParameter("name", name).executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM organizations WHERE name = :name")
                .setParameter("name", name).getSingleResult()).longValue();
    }

    private Long insertTeam(String name) {
        em.createNativeQuery(
                "INSERT INTO teams (name, visibility, supporter_enabled, version, member_count, slug, created_at, updated_at) "
                        + "VALUES (:name, 'PUBLIC', 1, 0, 0, CONCAT('s-', LEFT(REPLACE(UUID(),'-',''),8)), NOW(), NOW())")
                .setParameter("name", name).executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM teams WHERE name = :name")
                .setParameter("name", name).getSingleResult()).longValue();
    }

    private void insertTeamOrgMembership(Long teamIdParam, Long orgIdParam) {
        em.createNativeQuery(
                "INSERT INTO team_org_memberships (team_id, organization_id, status, invited_at, created_at) "
                        + "VALUES (:tid, :oid, 'ACTIVE', NOW(), NOW())")
                .setParameter("tid", teamIdParam).setParameter("oid", orgIdParam).executeUpdate();
    }

    private void insertUserRole(Long uid, Long roleIdParam, Long teamIdParam, Long orgIdParam) {
        em.createNativeQuery(
                "INSERT INTO user_roles (user_id, role_id, team_id, organization_id, created_at, updated_at) "
                        + "VALUES (:uid, :rid, :tid, :oid, NOW(), NOW())")
                .setParameter("uid", uid).setParameter("rid", roleIdParam)
                .setParameter("tid", teamIdParam).setParameter("oid", orgIdParam).executeUpdate();
    }

    private void insertMembership(Long uid, String scopeType, Long scopeId, String roleKind) {
        em.createNativeQuery(
                "INSERT INTO memberships (user_id, scope_type, scope_id, role_kind, joined_at, created_at, updated_at) "
                        + "VALUES (:uid, :st, :sid, :rk, NOW(), NOW(), NOW())")
                .setParameter("uid", uid).setParameter("st", scopeType)
                .setParameter("sid", scopeId).setParameter("rk", roleKind).executeUpdate();
    }

    private Long insertPaymentItem(String name, Long teamIdParam, Long orgIdParam) {
        em.createNativeQuery(
                "INSERT INTO payment_items (team_id, organization_id, name, type, amount, currency, "
                        + "is_active, display_order, grace_period_days, is_recurring, created_at, updated_at) "
                        + "VALUES (:tid, :oid, :name, 'ANNUAL_FEE', 1000.00, 'JPY', 1, 0, 0, 0, NOW(), NOW())")
                .setParameter("tid", teamIdParam).setParameter("oid", orgIdParam)
                .setParameter("name", name).executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM payment_items WHERE name = :name")
                .setParameter("name", name).getSingleResult()).longValue();
    }

    private void insertBeneficiarySetting(Long teamIdParam, Long orgIdParam, boolean memberOnly) {
        em.createNativeQuery(
                "INSERT INTO payment_beneficiary_settings "
                        + "(id, team_id, organization_id, beneficiary_member_only, created_at, updated_at) "
                        + "VALUES (UNHEX(REPLACE(UUID(),'-','')), :tid, :oid, :mo, NOW(6), NOW(6))")
                .setParameter("tid", teamIdParam).setParameter("oid", orgIdParam)
                .setParameter("mo", memberOnly ? 1 : 0).executeUpdate();
    }
}
