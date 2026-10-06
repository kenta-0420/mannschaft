package com.mannschaft.app.recruitment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.payment.connect.ConnectAccountEntity;
import com.mannschaft.app.payment.connect.ConnectAccountRepository;
import com.mannschaft.app.payment.connect.OnboardingStatus;
import com.mannschaft.app.payment.connect.ScopeKind;
import com.mannschaft.app.payment.escrow.EscrowCaptureMode;
import com.mannschaft.app.payment.escrow.EscrowSourceKind;
import com.mannschaft.app.payment.escrow.EscrowStatus;
import com.mannschaft.app.payment.escrow.EscrowTransactionEntity;
import com.mannschaft.app.payment.escrow.EscrowTransactionRepository;
import com.mannschaft.app.recruitment.entity.RecruitmentCancellationRecordEntity;
import com.mannschaft.app.recruitment.entity.RecruitmentCategoryEntity;
import com.mannschaft.app.recruitment.entity.RecruitmentListingEntity;
import com.mannschaft.app.recruitment.repository.RecruitmentCancellationRecordRepository;
import com.mannschaft.app.recruitment.repository.RecruitmentCategoryRepository;
import com.mannschaft.app.recruitment.repository.RecruitmentListingRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.MembershipTestHelper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 権限の無い主体の免除・一覧が 500（UnexpectedRollbackException）にならないことの回帰テスト。
 *
 * <p><b>このテストが {@code @Transactional} を付けない理由</b>: テスト全体を 1 つの tx で包むと、
 * AccessControlService（readOnly tx）は外側のテスト tx に合流するだけで、確定（commit）の局面が存在しない。
 * rollback-only の印は付いても UnexpectedRollbackException は起きず、欠陥を再現できない
 * （自分自身を測ってしまう罠）。本テストはデータを実際に commit し、リクエストごとに本番と同じ tx 境界で動かす。</p>
 *
 * <p>真因: 受取先判定が AccessControlService の check 系（例外を投げる）を try/catch して false に変換していた。
 * 例外が readOnly tx のプロキシ境界を越えた時点で外側 tx に rollback-only の印が付き、確定時に 500 となる。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("キャンセル料の受取先判定は権限なしでも tx を汚さない（免除 EP・一覧 EP の 500 回帰）")
class RecruitmentPayeeJudgementNoRollbackOnlyIT extends AbstractMySqlIntegrationTest {

    private static final long ABSENT_RECORD_ID = 999_999_999L;

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private RecruitmentCancellationRecordRepository cancellationRecordRepository;
    @Autowired private EscrowTransactionRepository escrowTransactionRepository;
    @Autowired private ConnectAccountRepository connectAccountRepository;
    @Autowired private RecruitmentListingRepository listingRepository;
    @Autowired private RecruitmentCategoryRepository categoryRepository;
    @Autowired private TransactionTemplate transactionTemplate;

    /** Stripe へ到達させない（本 IT は受取先判定の tx 挙動だけを見る）。 */
    @MockitoBean
    private com.mannschaft.app.payment.stripe.StripePaymentProvider stripePaymentProvider;

    @PersistenceContext
    private EntityManager em;

    private Long teamAId;
    private Long payeeAdminAId;
    /** teamA の一般メンバー（債務者ではない）。 */
    private Long plainMemberId;
    private Long debtorId;
    private Long outsiderId;
    private Long recordId;
    private Long listingId;
    private Long categoryId;
    private UUID teamAccountId;

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        transactionTemplate.executeWithoutResult(status -> {
            teamAId = insertTeam("RBONLY チーム " + suffix);
            payeeAdminAId = insertUser("rbonly-admin-" + suffix + "@example.com");
            plainMemberId = insertUser("rbonly-member-" + suffix + "@example.com");
            debtorId = insertUser("rbonly-debtor-" + suffix + "@example.com");
            outsiderId = insertUser("rbonly-outsider-" + suffix + "@example.com");

            MembershipTestHelper.insertMembership(em, payeeAdminAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
            MembershipTestHelper.insertUserRole(em, payeeAdminAId, "ADMIN", teamAId, null);
            MembershipTestHelper.insertMembership(em, plainMemberId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
            MembershipTestHelper.insertMembership(em, debtorId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
            grantManageRecruitmentsToAdmin();

            teamAccountId = insertConnectAccount(ScopeKind.TEAM, teamAId);
            categoryId = categoryRepository.save(RecruitmentCategoryEntity.builder()
                    .code("RBONLY_" + suffix)
                    .nameI18nKey("recruitment.category.rbonly")
                    .defaultParticipationType(RecruitmentParticipationType.INDIVIDUAL)
                    .displayOrder(0)
                    .isActive(true)
                    .build()).getId();
            listingId = insertListing(categoryId);
            recordId = insertRecord(listingId, 3001L, debtorId, CancellationPaymentStatus.PENDING);
            insertEscrow(listingId, 3001L, ScopeKind.TEAM, teamAccountId);
        });
    }

    /** commit 済みのデータを残さない（共有コンテナの他 IT を汚さない）。 */
    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        transactionTemplate.executeWithoutResult(status -> {
            em.createQuery("DELETE FROM RecruitmentCancellationRecordEntity r WHERE r.teamId = :t")
                    .setParameter("t", teamAId).executeUpdate();
            em.createQuery("DELETE FROM EscrowTransactionEntity e WHERE e.payeeConnectAccountId = :a")
                    .setParameter("a", teamAccountId).executeUpdate();
            em.createQuery("DELETE FROM RecruitmentListingEntity l WHERE l.id = :l")
                    .setParameter("l", listingId).executeUpdate();
            em.createQuery("DELETE FROM RecruitmentCategoryEntity c WHERE c.id = :c")
                    .setParameter("c", categoryId).executeUpdate();
            em.createQuery("DELETE FROM ConnectAccountEntity a WHERE a.id = :a")
                    .setParameter("a", teamAccountId).executeUpdate();
            em.createNativeQuery("DELETE FROM user_roles WHERE team_id = :t")
                    .setParameter("t", teamAId).executeUpdate();
            em.createNativeQuery("DELETE FROM memberships WHERE scope_id = :t AND scope_type = 'TEAM'")
                    .setParameter("t", teamAId).executeUpdate();
            for (Long u : List.of(payeeAdminAId, plainMemberId, debtorId, outsiderId)) {
                em.createNativeQuery("DELETE FROM users WHERE id = :u").setParameter("u", u).executeUpdate();
            }
            em.createNativeQuery("DELETE FROM teams WHERE id = :t").setParameter("t", teamAId).executeUpdate();
        });
    }

    @Test
    @DisplayName("部外者の免除は 500 ではなく不在と同じ 404（COMMON_005）")
    void 部外者の免除は404() throws Exception {
        setAuth(outsiderId);
        waive(recordId).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value(CommonErrorCode.COMMON_005.getCode()));
    }

    @Test
    @DisplayName("実在 ID と不在 ID で部外者の応答（ステータスとコード）が一致する")
    void 実在と不在で応答が一致する() throws Exception {
        setAuth(outsiderId);
        MvcResult existing = waive(recordId).andReturn();
        MvcResult absent = waive(ABSENT_RECORD_ID).andReturn();
        assertThat(existing.getResponse().getStatus()).isEqualTo(404).isEqualTo(absent.getResponse().getStatus());
        assertThat(errorCode(existing)).isEqualTo(errorCode(absent)).isEqualTo(CommonErrorCode.COMMON_005.getCode());
    }

    @Test
    @DisplayName("同スコープの一般メンバーの免除は 500 ではなく 403（COMMON_002）")
    void 一般メンバーの免除は403() throws Exception {
        setAuth(plainMemberId);
        waive(recordId).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value(CommonErrorCode.COMMON_002.getCode()));
    }

    @Test
    @DisplayName("一般メンバーの一覧取得は 500 ではなく 200")
    void 一般メンバーの一覧は200() throws Exception {
        setAuth(plainMemberId);
        mockMvc.perform(get("/api/v1/recruitment-cancellation-records").param("size", "50"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("対照: 受取先 ADMIN の免除は 200（判定が常に false になっていない）")
    void 受取先ADMINの免除は200() throws Exception {
        setAuth(payeeAdminAId);
        waive(recordId).andExpect(status().isOk());
    }

    @Test
    @DisplayName("対照: 受取先 ADMIN の一覧には当該記録が見える")
    void 受取先ADMINの一覧には記録が見える() throws Exception {
        setAuth(payeeAdminAId);
        String body = mockMvc.perform(get("/api/v1/recruitment-cancellation-records").param("size", "50"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(body).contains("\"id\":" + recordId);
    }

    private String errorCode(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("error").path("code").asText();
    }

    private ResultActions waive(Long id) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("reason", "回帰テスト");
        return mockMvc.perform(post("/api/v1/recruitment-cancellation-records/{recordId}/waive", id)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private void setAuth(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
    }

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

    private Long insertListing(Long categoryId) {
        LocalDateTime start = LocalDateTime.now().plusDays(30);
        return listingRepository.save(RecruitmentListingEntity.builder()
                .scopeType(RecruitmentScopeType.TEAM)
                .scopeId(teamAId)
                .categoryId(categoryId)
                .title("RBONLY 募集")
                .participationType(RecruitmentParticipationType.INDIVIDUAL)
                .startAt(start)
                .endAt(start.plusHours(2))
                .applicationDeadline(start.minusDays(1))
                .autoCancelAt(start.minusDays(2))
                .capacity(10)
                .minCapacity(1)
                .status(RecruitmentListingStatus.OPEN)
                .visibility(RecruitmentVisibility.SCOPE_ONLY)
                .createdBy(payeeAdminAId)
                .build()).getId();
    }

    private Long insertRecord(Long listingId, Long participantId, Long userId, CancellationPaymentStatus status) {
        return cancellationRecordRepository.save(RecruitmentCancellationRecordEntity.builder()
                .participantId(participantId)
                .listingId(listingId)
                .userId(userId)
                .teamId(teamAId)
                .cancelledAt(LocalDateTime.now())
                .cancelledBy(userId)
                .cancelSource(CancellationSource.USER)
                .hoursBeforeStart(6)
                .feeAmount(3_000)
                .paymentStatus(status)
                .build()).getId();
    }

    private void insertEscrow(Long listingId, Long participantId, ScopeKind payeeKind, UUID payeeAccountId) {
        escrowTransactionRepository.save(EscrowTransactionEntity.builder()
                .sourceKind(EscrowSourceKind.RECRUITMENT)
                .sourceId(listingId)
                .sourceParticipantId(participantId)
                .captureMode(EscrowCaptureMode.MANUAL)
                .payerScopeKind(ScopeKind.USER)
                .payerScopeId(debtorId)
                .payeeKind(payeeKind)
                .payeeConnectAccountId(payeeAccountId)
                .faceAmount(10_000L)
                .amount(10_250L)
                .applicationFeeAmount(250L)
                .currency("JPY")
                .feePolicyKey("RECRUITMENT_DEFAULT")
                .status(EscrowStatus.AUTHORIZED)
                .build());
    }

    private UUID insertConnectAccount(ScopeKind scopeKind, Long scopeId) {
        return connectAccountRepository.save(ConnectAccountEntity.builder()
                .scopeKind(scopeKind)
                .scopeId(scopeId)
                .stripeAccountId("acct_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16))
                .onboardingStatus(OnboardingStatus.READY)
                .chargesEnabled(true)
                .payoutsEnabled(true)
                .build()).getId();
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
                                + "VALUES (:email, 'RBONLY', 'テスト', 'RBONLY テスト', 'ACTIVE', "
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
                                + "CONCAT('rbonly-', LEFT(REPLACE(UUID(),'-',''),8)), NOW(), NOW())")
                .setParameter("name", name)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM teams WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult()).longValue();
    }
}
