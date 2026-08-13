package com.mannschaft.app.recruitment;

import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.recruitment.entity.RecruitmentCancellationRecordEntity;
import com.mannschaft.app.recruitment.entity.RecruitmentListingEntity;
import com.mannschaft.app.recruitment.repository.RecruitmentCancellationRecordRepository;
import com.mannschaft.app.recruitment.repository.RecruitmentListingRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.MembershipTestHelper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F03.11.1 キャンセル料記録の一覧 EP の認可契約テスト（設計書 §12.2・マスター裁定 2026-08-13）。
 *
 * <p><b>この EP が守るべきこと</b>: 一覧の絞り込みは {@code recruitment_listings} 自身が持つ
 * {@code payeeKind}/{@code payeeUserId}/{@code scopeId} だけで行う（escrow は読まない）。
 * TEAM/ORG/個人（USER）の 3 通りすべてで「受取先側には見える」「無関係な他者には見えない」を
 * 対で検証する——肯定側だけでは判定が常に true でも緑になる。{@code SYSTEM_ADMIN} は全件見える。</p>
 *
 * <p>金型: {@link RecruitmentCancellationFeeWaiveContractIT}（免除 EP の認可契約テストと対）。
 * 一覧は「絞り込まれた閲覧」に過ぎず、免除できるかどうかの最終判定ではない
 * （二段構え・免除側の認可契約は {@link RecruitmentCancellationFeeWaiveContractIT} が担う）。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("F03.11.1 キャンセル料記録一覧 認可契約テスト（受取先絞り込み）")
class RecruitmentCancellationRecordListContractIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RecruitmentCancellationRecordRepository cancellationRecordRepository;

    @Autowired
    private RecruitmentListingRepository listingRepository;

    @PersistenceContext
    private EntityManager em;

    private Long teamAId;
    private Long teamBId;
    private Long orgId;

    /** teamA（受取先）の ADMIN。teamA 受取の記録が見える。 */
    private Long teamAAdminId;
    /** teamB の ADMIN。teamA/org/個人受取の記録には無関係＝見えない。 */
    private Long teamBAdminId;
    /** org（受取先）の ADMIN。org 受取の記録が見える。 */
    private Long orgAdminId;
    /** 受取先が個人（payeeKind=USER）の記録における受取本人。 */
    private Long individualPayeeId;
    /** キャンセル料を負っている本人（債務者）。どの記録の受取先でもない。 */
    private Long debtorId;
    /** どこにも所属しない部外者。 */
    private Long outsiderId;
    /** SYSTEM_ADMIN。全件見える。 */
    private Long systemAdminId;

    private Long teamRecordId;
    private Long orgRecordId;
    private Long userRecordId;
    private Long teamBRecordId;

    @BeforeEach
    void setUp() {
        teamAId = insertTeam("CANFEELIST チームA");
        teamBId = insertTeam("CANFEELIST チームB");
        orgId = insertOrganization("CANFEELIST 組織");

        teamAAdminId = insertUser("canfeelist-team-a-admin@example.com");
        teamBAdminId = insertUser("canfeelist-team-b-admin@example.com");
        orgAdminId = insertUser("canfeelist-org-admin@example.com");
        individualPayeeId = insertUser("canfeelist-individual-payee@example.com");
        debtorId = insertUser("canfeelist-debtor@example.com");
        outsiderId = insertUser("canfeelist-outsider@example.com");
        systemAdminId = insertUser("canfeelist-system-admin@example.com");

        MembershipTestHelper.insertMembership(em, teamAAdminId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, teamAAdminId, "ADMIN", teamAId, null);
        MembershipTestHelper.insertMembership(em, teamBAdminId, ScopeType.TEAM, teamBId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, teamBAdminId, "ADMIN", teamBId, null);
        MembershipTestHelper.insertUserRole(em, orgAdminId, "ADMIN", null, orgId);
        MembershipTestHelper.insertUserRole(em, systemAdminId, "SYSTEM_ADMIN", null, null);

        grantManageRecruitmentsToAdmin();

        Long teamListingId = insertListing("TEAM", teamAId, "USER".equals("TEAM") ? null : null, teamAId, "TEAM", null);
        Long orgListingId = insertListing("ORGANIZATION", orgId, null, orgId, "ORG", null);
        Long userListingId = insertListing("TEAM", teamAId, null, teamAId, "USER", individualPayeeId);
        Long teamBListingId = insertListing("TEAM", teamBId, null, teamBId, "TEAM", null);

        teamRecordId = insertRecord(teamListingId, 3001L, debtorId, CancellationPaymentStatus.PENDING);
        orgRecordId = insertRecord(orgListingId, 3002L, debtorId, CancellationPaymentStatus.PENDING);
        userRecordId = insertRecord(userListingId, 3003L, debtorId, CancellationPaymentStatus.PENDING);
        teamBRecordId = insertRecord(teamBListingId, 3004L, debtorId, CancellationPaymentStatus.PENDING);

        em.flush();
        em.clear();
    }

    // ═════════════════════════════════════════════════════════════════════
    // 1. 受取先が TEAM
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("1. 受取先が TEAM のとき")
    class TeamPayee {

        @Test
        @DisplayName("肯定: 受取先 TEAM の ADMIN には TEAM 受取の記録が見える")
        void 受取先TEAMのADMINには見える() throws Exception {
            setAuth(teamAAdminId);
            List<Long> ids = listRecordIds();
            assertThat(ids).contains(teamRecordId);
        }

        @Test
        @DisplayName("否定: 無関係な TEAM の ADMIN には見えない（テナント越境の遮断）")
        void 無関係TEAMのADMINには見えない() throws Exception {
            setAuth(teamBAdminId);
            List<Long> ids = listRecordIds();
            assertThat(ids).doesNotContain(teamRecordId, orgRecordId, userRecordId);
            // 自分（teamB）受取の記録だけは見える
            assertThat(ids).contains(teamBRecordId);
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 2. 受取先が ORG
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("2. 受取先が ORG のとき")
    class OrgPayee {

        @Test
        @DisplayName("肯定: 受取先 ORG の ADMIN には ORG 受取の記録が見える")
        void 受取先ORGのADMINには見える() throws Exception {
            setAuth(orgAdminId);
            List<Long> ids = listRecordIds();
            assertThat(ids).contains(orgRecordId);
        }

        @Test
        @DisplayName("否定: ORG に無関係な TEAM の ADMIN には見えない")
        void 無関係な者には見えない() throws Exception {
            setAuth(teamBAdminId);
            List<Long> ids = listRecordIds();
            assertThat(ids).doesNotContain(orgRecordId);
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 3. 受取先が個人（payeeKind=USER）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("3. 受取先が個人のとき")
    class UserPayee {

        @Test
        @DisplayName("肯定: 受取本人には見える")
        void 受取本人には見える() throws Exception {
            setAuth(individualPayeeId);
            List<Long> ids = listRecordIds();
            assertThat(ids).contains(userRecordId);
        }

        @Test
        @DisplayName("否定: 他人には見えない")
        void 他人には見えない() throws Exception {
            setAuth(teamBAdminId);
            List<Long> ids = listRecordIds();
            assertThat(ids).doesNotContain(userRecordId);
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 4. 債務者・部外者・SYSTEM_ADMIN
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("4. その他の主体")
    class Others {

        /** 債務者本人は受取先でも運営でもないため、一覧には何も見えない（本波では債務者向け一覧を作らない）。 */
        @Test
        @DisplayName("債務者本人には何も見えない（本波のスコープ外）")
        void 債務者には見えない() throws Exception {
            setAuth(debtorId);
            List<Long> ids = listRecordIds();
            assertThat(ids).doesNotContain(teamRecordId, orgRecordId, userRecordId, teamBRecordId);
        }

        @Test
        @DisplayName("何の権限も持たない部外者には何も見えない")
        void 部外者には見えない() throws Exception {
            setAuth(outsiderId);
            List<Long> ids = listRecordIds();
            assertThat(ids).doesNotContain(teamRecordId, orgRecordId, userRecordId, teamBRecordId);
        }

        @Test
        @DisplayName("SYSTEM_ADMIN には全件見える")
        void SYSTEM_ADMINには全件見える() throws Exception {
            setAuth(systemAdminId);
            List<Long> ids = listRecordIds();
            assertThat(ids).contains(teamRecordId, orgRecordId, userRecordId, teamBRecordId);
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // ヘルパー
    // ═════════════════════════════════════════════════════════════════════

    private List<Long> listRecordIds() throws Exception {
        String body = mockMvc.perform(get("/api/v1/recruitment-cancellation-records")
                        .param("page", "0")
                        .param("size", "50"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\"id\":(\\d+)").matcher(body);
        List<Long> ids = new java.util.ArrayList<>();
        while (matcher.find()) {
            ids.add(Long.valueOf(matcher.group(1)));
        }
        return ids;
    }

    private void setAuth(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
    }

    private Long insertRecord(Long listingId, Long participantId, Long userId, CancellationPaymentStatus status) {
        return cancellationRecordRepository.save(RecruitmentCancellationRecordEntity.builder()
                .participantId(participantId)
                .listingId(listingId)
                .userId(userId)
                .cancelledAt(LocalDateTime.now())
                .cancelledBy(userId)
                .cancelSource(CancellationSource.USER)
                .hoursBeforeStart(6)
                .feeAmount(3_000)
                .paymentStatus(status)
                .build()).getId();
    }

    private Long insertListing(String scopeTypeName, Long scopeId, Long unusedA, Long unusedB,
            String payeeKind, Long payeeUserId) {
        LocalDateTime start = LocalDateTime.now().plusDays(30);
        return listingRepository.save(RecruitmentListingEntity.builder()
                .scopeType(RecruitmentScopeType.valueOf(scopeTypeName))
                .scopeId(scopeId)
                .categoryId(1L)
                .title("CANFEELIST 募集")
                .participationType(RecruitmentParticipationType.INDIVIDUAL)
                .startAt(start)
                .endAt(start.plusHours(2))
                .applicationDeadline(start.minusDays(1))
                .autoCancelAt(start.minusDays(2))
                .capacity(10)
                .minCapacity(1)
                .status(RecruitmentListingStatus.OPEN)
                .paymentEnabled(true)
                .price(5_000)
                .payeeKind(payeeKind)
                .payeeUserId(payeeUserId)
                .createdBy(scopeId)
                .build()).getId();
    }

    /** {@code MANAGE_RECRUITMENTS} を権限カタログへ登録し ADMIN へ自動付与する（本番マイグレーションの写し）。 */
    private void grantManageRecruitmentsToAdmin() {
        em.createNativeQuery(
                        "INSERT INTO permissions (name, display_name, scope, created_at, updated_at) "
                                + "SELECT 'MANAGE_RECRUITMENTS', '募集（札）管理', 'TEAM', NOW(), NOW() FROM DUAL "
                                + "WHERE NOT EXISTS (SELECT 1 FROM permissions WHERE name = 'MANAGE_RECRUITMENTS')")
                .executeUpdate();
        em.createNativeQuery(
                        "INSERT INTO role_permissions (role_id, permission_id, is_default, created_at) "
                                + "SELECT r.id, p.id, 1, NOW() FROM roles r CROSS JOIN permissions p "
                                + "WHERE r.name = 'ADMIN' AND p.name = 'MANAGE_RECRUITMENTS' "
                                + "AND NOT EXISTS (SELECT 1 FROM role_permissions rp "
                                + "  WHERE rp.role_id = r.id AND rp.permission_id = p.id)")
                .executeUpdate();
    }

    private Long insertUser(String email) {
        em.createNativeQuery(
                        "INSERT INTO users ("
                                + "email, last_name, first_name, display_name, status, "
                                + "is_searchable, handle_searchable, contact_approval_required, "
                                + "online_visibility, dm_receive_from, encryption_key_version, "
                                + "locale, timezone, reporting_restricted, follow_list_visibility, "
                                + "care_notification_enabled, offline_only, "
                                + "created_at, updated_at) "
                                + "VALUES (:email, 'CANFEELIST', 'テスト', 'CANFEELIST テスト', 'ACTIVE', "
                                + "1, 1, 1, "
                                + "'NOBODY', 'ANYONE', 1, "
                                + "'ja', 'Asia/Tokyo', 0, 'PUBLIC', "
                                + "1, 0, "
                                + "NOW(), NOW())")
                .setParameter("email", email)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM users WHERE email = :email")
                .setParameter("email", email)
                .getSingleResult()).longValue();
    }

    private Long insertTeam(String name) {
        em.createNativeQuery(
                        "INSERT INTO teams (name, visibility, supporter_enabled, version, member_count, slug, "
                                + "created_at, updated_at) "
                                + "VALUES (:name, 'PUBLIC', 1, 0, 0, "
                                + "CONCAT('canfeelist-', LEFT(REPLACE(UUID(),'-',''),8)), NOW(), NOW())")
                .setParameter("name", name)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM teams WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult()).longValue();
    }

    private Long insertOrganization(String name) {
        em.createNativeQuery(
                        "INSERT INTO organizations (name, visibility, version, slug, created_at, updated_at) "
                                + "VALUES (:name, 'PUBLIC', 0, "
                                + "CONCAT('canfeelist-org-', LEFT(REPLACE(UUID(),'-',''),8)), NOW(), NOW())")
                .setParameter("name", name)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM organizations WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult()).longValue();
    }
}
