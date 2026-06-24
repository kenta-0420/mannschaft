package com.mannschaft.app.payment;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.payment.dto.BulkPaymentRequest;
import com.mannschaft.app.payment.dto.BulkPaymentResponse;
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

/**
 * F08.9 会費手動入金の「認可」結合テスト（実 MySQL ＋ 実 {@link com.mannschaft.app.common.AccessControlService}）。
 *
 * <p><b>目的:</b> 既存の純 Mockito テスト（{@code MemberPaymentServiceTest}）は
 * {@code AccessControlService.isAdminOrAbove}/{@code isMemberOrDescendant} をモックしているため
 * 「実メンバーシップ・実認可で本当に拒否されるか」を裏取りできない。本テストは
 * Testcontainers MySQL に最小 seed（user_roles の TEAM ADMIN・memberships の MEMBER/SUPPORTER）を直接 INSERT し、
 * {@link MemberPaymentService} を実 Bean で呼んで以下を検証する:</p>
 *
 * <ul>
 *   <li><b>AC-A1</b>: 一括入金は非 ADMIN（member でも）なら {@code MEMBERSHIP_PAYER_NOT_AUTHORIZED}（403）で拒否。</li>
 *   <li><b>AC-A2</b>: 一括入金は scope ADMIN なら正常（createdCount&gt;0）。</li>
 *   <li><b>AC-A4</b>: 認可失敗時は DB へ 0 件保存（ロールバック＝部分保存なし）。</li>
 *   <li><b>AC-6a</b>: 単一入金で受益者がスコープ非所属 → {@code USER_NOT_MEMBER}（PAYMENT_027）。</li>
 *   <li><b>AC-6b</b>: 単一入金で受益者が MEMBER → 正常。</li>
 *   <li><b>AC-6c</b>: 一括 [member, nonMember] → created=1・skipped=1（USER_NOT_MEMBER 理由）。</li>
 *   <li><b>AC-6e</b>: 受益者が（組織配下の）純 SUPPORTER → {@code USER_NOT_MEMBER}（includeSupporters=false で除外）。</li>
 * </ul>
 *
 * <p><b>seed 設計（実コード照合の根拠）:</b></p>
 * <ul>
 *   <li><b>ADMIN 判定</b>（{@code isAdminOrAbove} → {@code getRoleName} → {@code resolveEffectiveRoleName}）は
 *       <b>user_roles</b> 由来の権限ロールを見る。よって admin は {@code user_roles(team_id, role=ADMIN)} で seed する
 *       （{@code AccessControlService.findUserRole} が {@code findByUserIdAndTeamId} を引く）。</li>
 *   <li><b>受益者所属判定</b>（{@code isMemberOrDescendant(..., false)} → TEAM スコープでは {@code isMember}）は
 *       <b>memberships</b> を見る（{@code MembershipRepository.existsActiveByUserAndScope}・role_kind 非フィルタ・
 *       {@code left_at IS NULL}）。よって member は {@code memberships(TEAM, MEMBER)} で seed し、
 *       nonMember は memberships 行なしにする。</li>
 *   <li><b>SUPPORTER の除外（AC-6e）</b>は TEAM スコープでは成立しない（{@code existsActiveByUserAndScope} が
 *       role_kind を絞らないため TEAM の SUPPORTER 行でも {@code isMember}=true になる）。純 SUPPORTER が
 *       実際に弾かれるのは <b>ORGANIZATION スコープ</b>の応答母集団判定
 *       （{@code isInOrgDistributionAudience(org, user, includeSupporters=false)}）であり、これは
 *       「組織に直接 membership を持たず、配下チームに SUPPORTER membership ＋ user_roles を持つ」ユーザーを除外する。
 *       よって AC-6e は ORGANIZATION スコープの payment_item ＋ 配下チームの純 SUPPORTER で seed する。</li>
 * </ul>
 *
 * <p>seed ヘルパーは {@code MembershipBatchQueryServiceIntegrationTest}（{@code em.createNativeQuery} 直 INSERT・
 * {@code @Transactional} ロールバック方式）を踏襲する。{@code application-test} は {@code ddl-auto: create} ＋
 * {@code flyway.enabled=false} のため、{@code roles} 等のマスタも本テストで直接 seed する。</p>
 */
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("会費手動入金 認可 結合テスト（実 MySQL・実 AccessControlService）")
class MemberPaymentAuthzIntegrationTest extends AbstractMySqlIntegrationTest {

    @Autowired
    private MemberPaymentService memberPaymentService;

    @Autowired
    private MemberPaymentRepository memberPaymentRepository;

    @PersistenceContext
    private EntityManager em;

    private Long teamId;
    private Long orgId;
    private Long descendantTeamId;

    private Long adminUserId;    // user_roles: TEAM ADMIN（払い手として権原あり）
    private Long memberUserId;   // memberships: TEAM MEMBER（受益者として所属）
    private Long nonMemberUserId; // membership 行なし（受益者として非所属）
    private Long supporterUserId; // 組織配下チームの純 SUPPORTER（受益者として ORG 応答母集団から除外される）

    private Long teamItemId;      // TEAM スコープ ANNUAL_FEE
    private Long orgItemId;       // ORGANIZATION スコープ ANNUAL_FEE（AC-6e 用）

    @BeforeEach
    void setUp() {
        // 1. ロールマスタ（ddl-auto:create ＝ Flyway 無効環境のため直接 seed）。
        //    priority は roles テーブル準拠: ADMIN(2) < MEMBER(4) < SUPPORTER(5)。
        //    resolveEffectiveRole が priority 最小（最強）を採るため ADMIN が MEMBER より強い必要がある。
        insertRole("ADMIN", "管理者", 2, true);
        insertRole("MEMBER", "メンバー", 4, false);
        insertRole("SUPPORTER", "サポーター", 5, false);
        em.flush();
        Long adminRoleId = roleId("ADMIN");

        // 2. スコープ（チーム・組織・配下チーム）
        teamId = insertTeam("認可テスト チーム");
        orgId = insertOrganization("認可テスト 組織");
        descendantTeamId = insertTeam("認可テスト 配下チーム");
        // 配下チーム → 組織（ACTIVE）。ORG 応答母集団の再帰展開で配下チーム所属者を母集団に含めるため必須。
        insertTeamOrgMembership(descendantTeamId, orgId);

        // 3. ユーザー
        adminUserId = insertUser("authz.admin@example.com", "管理", "者");
        memberUserId = insertUser("authz.member@example.com", "会員", "太郎");
        nonMemberUserId = insertUser("authz.nonmember@example.com", "部外", "者");
        supporterUserId = insertUser("authz.supporter@example.com", "支援", "花子");

        // 4. 払い手 admin: user_roles に TEAM ADMIN（isAdminOrAbove(adminUserId, teamId, "TEAM")=true）。
        insertUserRole(adminUserId, adminRoleId, teamId, null);
        //    admin は ORG スコープでも ADMIN（AC-6e の払い手権原のため user_roles に ORG ADMIN も付与）。
        insertUserRole(adminUserId, adminRoleId, null, orgId);

        // 5. 受益者の所属（memberships）。
        //    member: TEAM MEMBER（isMember(memberUserId, teamId, "TEAM")=true）。
        insertMembership(memberUserId, "TEAM", teamId, "MEMBER");
        //    nonMember: membership 行なし（isMember=false）。
        //    supporter: 配下チームの純 SUPPORTER。組織に直接 membership は持たない。
        //      → isMember(supporterUserId, orgId, "ORGANIZATION")=false（直接 ORG membership なし）。
        //      → isInOrgDistributionAudience(orgId, supporterUserId, false)=false（純 SUPPORTER 除外）。
        insertMembership(supporterUserId, "TEAM", descendantTeamId, "SUPPORTER");
        //    isInOrgDistributionAudience の母集団判定は user_roles(team_id=配下) を見るため、SUPPORTER にも
        //    配下チームの user_roles を付与する（権限ロールではなく所属の足場。SUPPORTER 行＝memberships で除外される）。
        Long supporterRoleId = roleId("SUPPORTER");
        insertUserRole(supporterUserId, supporterRoleId, descendantTeamId, null);

        // 6. payment_item（TEAM / ORGANIZATION スコープの ANNUAL_FEE）
        teamItemId = insertPaymentItem("年会費（チーム）", teamId, null);
        orgItemId = insertPaymentItem("年会費（組織）", null, orgId);

        em.flush();
        em.clear();
    }

    // =========================================================================
    // AC-A1 / AC-A2 / AC-A4: 一括入金の払い手 ADMIN 権原
    // =========================================================================

    @Test
    @DisplayName("[AC-A1] 一括入金: 払い手が非 ADMIN（member）→ MEMBERSHIP_PAYER_NOT_AUTHORIZED で拒否")
    void AC_A1_一括入金は非ADMINを拒否() {
        BulkPaymentRequest req = bulkReq(memberUserId);

        // member（user_roles の ADMIN ロールなし）が払い手 → authorizeBulkPaymentByAdmin が 403。
        assertThatThrownBy(() -> memberPaymentService.createBulkPayments(teamItemId, memberUserId, req))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(MembershipBillingErrorCode.MEMBERSHIP_PAYER_NOT_AUTHORIZED);
    }

    @Test
    @DisplayName("[AC-A2] 一括入金: 払い手が scope ADMIN・受益者が MEMBER → 正常（createdCount>0）")
    void AC_A2_一括入金はADMINなら正常() {
        BulkPaymentRequest req = bulkReq(memberUserId);

        BulkPaymentResponse res = memberPaymentService.createBulkPayments(teamItemId, adminUserId, req);

        assertThat(res.getCreatedCount()).isGreaterThan(0);
        assertThat(res.getCreatedCount()).isEqualTo(1);
        assertThat(res.getSkippedCount()).isZero();
        assertThat(memberPaymentRepository.findByPaymentItemId(teamItemId)).hasSize(1);
    }

    @Test
    @DisplayName("[AC-A4] 一括入金: 認可失敗（非 ADMIN）時は DB へ 0 件保存（部分保存なし）")
    void AC_A4_認可失敗時は0件保存() {
        BulkPaymentRequest req = bulkReq(memberUserId);

        assertThatThrownBy(() -> memberPaymentService.createBulkPayments(teamItemId, memberUserId, req))
                .isInstanceOf(BusinessException.class);

        // authorizeBulkPaymentByAdmin はループ前に1度だけ評価し、403 で即 throw する。1件も保存されていないこと。
        assertThat(memberPaymentRepository.findByPaymentItemId(teamItemId)).isEmpty();
    }

    // =========================================================================
    // AC-6a / AC-6b / AC-6c: 単一・一括の受益者スコープ所属検証
    // =========================================================================

    @Test
    @DisplayName("[AC-6a] 単一入金: 受益者がスコープ非所属 → USER_NOT_MEMBER(PAYMENT_027)")
    void AC_6a_受益者非所属はUSER_NOT_MEMBER() {
        CreateManualPaymentRequest req = manualReq(nonMemberUserId);

        // 払い手は ADMIN（権原 OK）だが受益者 nonMember は memberships 行なし → verifyBeneficiaryMembership が 403/027。
        assertThatThrownBy(() -> memberPaymentService.createManualPayment(teamItemId, adminUserId, req))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(PaymentErrorCode.USER_NOT_MEMBER);

        assertThat(memberPaymentRepository.findByPaymentItemId(teamItemId)).isEmpty();
    }

    @Test
    @DisplayName("[AC-6b] 単一入金: 受益者が MEMBER → 正常作成")
    void AC_6b_受益者MEMBERは正常() {
        CreateManualPaymentRequest req = manualReq(memberUserId);

        var res = memberPaymentService.createManualPayment(teamItemId, adminUserId, req);

        assertThat(res).isNotNull();
        assertThat(res.getUserId()).isEqualTo(memberUserId);
        List<MemberPaymentEntity> saved = memberPaymentRepository.findByPaymentItemId(teamItemId);
        assertThat(saved).hasSize(1);
        assertThat(saved.get(0).getUserId()).isEqualTo(memberUserId);
    }

    @Test
    @DisplayName("[AC-6c] 一括入金: [member, nonMember] → created=1・skipped=1（USER_NOT_MEMBER 理由）")
    void AC_6c_一括は非所属をスキップし所属分はcreated() {
        BulkPaymentRequest req = bulkReq(memberUserId, nonMemberUserId);

        BulkPaymentResponse res = memberPaymentService.createBulkPayments(teamItemId, adminUserId, req);

        assertThat(res.getCreatedCount()).isEqualTo(1);
        assertThat(res.getSkippedCount()).isEqualTo(1);
        assertThat(res.getSkipped()).hasSize(1);
        assertThat(res.getSkipped().get(0).getUserId()).isEqualTo(nonMemberUserId);
        assertThat(res.getSkipped().get(0).getReason()).isEqualTo(PaymentErrorCode.USER_NOT_MEMBER.getCode());

        List<MemberPaymentEntity> saved = memberPaymentRepository.findByPaymentItemId(teamItemId);
        assertThat(saved).hasSize(1);
        assertThat(saved.get(0).getUserId()).isEqualTo(memberUserId);
    }

    // =========================================================================
    // AC-6e: 純 SUPPORTER は includeSupporters=false で除外（ORGANIZATION スコープ）
    // =========================================================================

    @Test
    @DisplayName("[AC-6e] 単一入金: 受益者が組織配下の純 SUPPORTER → USER_NOT_MEMBER（includeSupporters=false で除外）")
    void AC_6e_純SUPPORTER受益者は拒否() {
        CreateManualPaymentRequest req = manualReq(supporterUserId);

        // 払い手は ORG ADMIN（権原 OK）。受益者 supporter は組織に直接 membership を持たず（isMember=false）、
        // 配下チームの純 SUPPORTER ゆえ isInOrgDistributionAudience(false)=false → isMemberOrDescendant=false → 027。
        assertThatThrownBy(() -> memberPaymentService.createManualPayment(orgItemId, adminUserId, req))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(PaymentErrorCode.USER_NOT_MEMBER);

        assertThat(memberPaymentRepository.findByPaymentItemId(orgItemId)).isEmpty();
    }

    // =========================================================================
    // リクエスト DTO ビルダ
    // =========================================================================

    private CreateManualPaymentRequest manualReq(Long userId) {
        return new CreateManualPaymentRequest(
                userId,
                new BigDecimal("1000.00"),
                LocalDateTime.now(),
                null,    // validFrom
                null,    // validUntil
                null,    // note
                null);   // paymentMethod → MANUAL フォールバック
    }

    private BulkPaymentRequest bulkReq(Long... userIds) {
        List<CreateManualPaymentRequest> payments = java.util.Arrays.stream(userIds)
                .map(this::manualReq)
                .toList();
        return new BulkPaymentRequest(payments);
    }

    // =========================================================================
    // seed ヘルパー（MembershipBatchQueryServiceIntegrationTest 踏襲）
    // =========================================================================

    private void insertRole(String name, String displayName, int priority, boolean isSystem) {
        em.createNativeQuery(
                "INSERT INTO roles (name, display_name, priority, is_system, created_at, updated_at) "
                        + "VALUES (:name, :dn, :pri, :sys, NOW(), NOW())")
                .setParameter("name", name)
                .setParameter("dn", displayName)
                .setParameter("pri", priority)
                .setParameter("sys", isSystem ? 1 : 0)
                .executeUpdate();
    }

    private Long roleId(String name) {
        return ((Number) em.createNativeQuery("SELECT id FROM roles WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult()).longValue();
    }

    private Long insertUser(String email, String lastName, String firstName) {
        em.createNativeQuery(
                "INSERT INTO users (" +
                        "email, last_name, first_name, display_name, status, " +
                        "is_searchable, handle_searchable, contact_approval_required, " +
                        "online_visibility, dm_receive_from, encryption_key_version, " +
                        "locale, timezone, reporting_restricted, follow_list_visibility, " +
                        "care_notification_enabled, offline_only, " +
                        "created_at, updated_at) " +
                        "VALUES (:email, :ln, :fn, :dn, 'ACTIVE', " +
                        "1, 1, 1, " +
                        "'NOBODY', 'ANYONE', 1, " +
                        "'ja', 'Asia/Tokyo', 0, 'PUBLIC', " +
                        "1, 0, " +
                        "NOW(), NOW())")
                .setParameter("email", email)
                .setParameter("ln", lastName)
                .setParameter("fn", firstName)
                .setParameter("dn", lastName + " " + firstName)
                .executeUpdate();
        return ((Number) em.createNativeQuery(
                "SELECT id FROM users WHERE email = :email")
                .setParameter("email", email)
                .getSingleResult()).longValue();
    }

    private Long insertOrganization(String name) {
        em.createNativeQuery(
                "INSERT INTO organizations (name, org_type, visibility, hierarchy_visibility, " +
                        "supporter_enabled, version, slug, created_at, updated_at) " +
                        "VALUES (:name, 'OTHER', 'PUBLIC', 'NONE', 1, 0, " +
                        "CONCAT('s-', LEFT(REPLACE(UUID(),'-',''),8)), NOW(), NOW())")
                .setParameter("name", name)
                .executeUpdate();
        return ((Number) em.createNativeQuery(
                "SELECT id FROM organizations WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult()).longValue();
    }

    private Long insertTeam(String name) {
        em.createNativeQuery(
                "INSERT INTO teams (name, visibility, supporter_enabled, version, member_count, slug, created_at, updated_at) " +
                        "VALUES (:name, 'PUBLIC', 1, 0, 0, CONCAT('s-', LEFT(REPLACE(UUID(),'-',''),8)), NOW(), NOW())")
                .setParameter("name", name)
                .executeUpdate();
        return ((Number) em.createNativeQuery(
                "SELECT id FROM teams WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult()).longValue();
    }

    private void insertTeamOrgMembership(Long teamIdParam, Long orgIdParam) {
        em.createNativeQuery(
                "INSERT INTO team_org_memberships (team_id, organization_id, status, invited_at, created_at) " +
                        "VALUES (:tid, :oid, 'ACTIVE', NOW(), NOW())")
                .setParameter("tid", teamIdParam)
                .setParameter("oid", orgIdParam)
                .executeUpdate();
    }

    private void insertUserRole(Long uid, Long roleIdParam, Long teamIdParam, Long orgIdParam) {
        em.createNativeQuery(
                "INSERT INTO user_roles (user_id, role_id, team_id, organization_id, created_at, updated_at) " +
                        "VALUES (:uid, :rid, :tid, :oid, NOW(), NOW())")
                .setParameter("uid", uid)
                .setParameter("rid", roleIdParam)
                .setParameter("tid", teamIdParam)
                .setParameter("oid", orgIdParam)
                .executeUpdate();
    }

    private void insertMembership(Long uid, String scopeType, Long scopeId, String roleKind) {
        em.createNativeQuery(
                "INSERT INTO memberships (user_id, scope_type, scope_id, role_kind, joined_at, created_at, updated_at) " +
                        "VALUES (:uid, :st, :sid, :rk, NOW(), NOW(), NOW())")
                .setParameter("uid", uid)
                .setParameter("st", scopeType)
                .setParameter("sid", scopeId)
                .setParameter("rk", roleKind)
                .executeUpdate();
    }

    private Long insertPaymentItem(String name, Long teamIdParam, Long orgIdParam) {
        em.createNativeQuery(
                "INSERT INTO payment_items (" +
                        "team_id, organization_id, name, type, amount, currency, " +
                        "is_active, display_order, grace_period_days, is_recurring, created_at, updated_at) " +
                        "VALUES (:tid, :oid, :name, 'ANNUAL_FEE', 1000.00, 'JPY', " +
                        "1, 0, 0, 0, NOW(), NOW())")
                .setParameter("tid", teamIdParam)
                .setParameter("oid", orgIdParam)
                .setParameter("name", name)
                .executeUpdate();
        return ((Number) em.createNativeQuery(
                "SELECT id FROM payment_items WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult()).longValue();
    }
}
