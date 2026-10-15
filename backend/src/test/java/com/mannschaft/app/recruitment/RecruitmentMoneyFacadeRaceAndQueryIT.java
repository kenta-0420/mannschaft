package com.mannschaft.app.recruitment;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.ErrorCode;
import com.mannschaft.app.common.visibility.perf.SqlIntentCounter;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.payment.connect.ConnectAccountEntity;
import com.mannschaft.app.payment.connect.ConnectAccountRepository;
import com.mannschaft.app.payment.connect.OnboardingStatus;
import com.mannschaft.app.payment.connect.ScopeKind;
import com.mannschaft.app.payment.escrow.ConnectChargeService;
import com.mannschaft.app.payment.escrow.EscrowCaptureMode;
import com.mannschaft.app.payment.escrow.EscrowSourceKind;
import com.mannschaft.app.payment.escrow.EscrowStatus;
import com.mannschaft.app.payment.escrow.EscrowTransactionEntity;
import com.mannschaft.app.payment.escrow.EscrowTransactionRepository;
import com.mannschaft.app.recruitment.entity.RecruitmentCancellationPolicyEntity;
import com.mannschaft.app.recruitment.entity.RecruitmentCancellationPolicyTierEntity;
import com.mannschaft.app.recruitment.entity.RecruitmentCancellationRecordEntity;
import com.mannschaft.app.recruitment.entity.RecruitmentCategoryEntity;
import com.mannschaft.app.recruitment.entity.RecruitmentListingEntity;
import com.mannschaft.app.recruitment.entity.RecruitmentParticipantEntity;
import com.mannschaft.app.recruitment.entity.RecruitmentPenaltySettingEntity;
import com.mannschaft.app.recruitment.entity.RecruitmentUserPenaltyEntity;
import com.mannschaft.app.recruitment.repository.RecruitmentCancellationPolicyRepository;
import com.mannschaft.app.recruitment.repository.RecruitmentCancellationPolicyTierRepository;
import com.mannschaft.app.recruitment.repository.RecruitmentCancellationRecordRepository;
import com.mannschaft.app.recruitment.repository.RecruitmentCategoryRepository;
import com.mannschaft.app.recruitment.repository.RecruitmentListingRepository;
import com.mannschaft.app.recruitment.repository.RecruitmentParticipantRepository;
import com.mannschaft.app.recruitment.repository.RecruitmentPenaltySettingRepository;
import com.mannschaft.app.recruitment.repository.RecruitmentUserPenaltyRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.MembershipTestHelper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mockito;
import org.mockito.stubbing.Answer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * recruitment の金銭・制裁 6 EP を「認可（tx の外の Facade）→ tx 本体」に分けた型（CMP-260923-0954 W4 / plan4）の
 * <b>競合・ロック順・クエリ回数</b>の契約テスト。
 *
 * <p>{@code *ScopeContractIT} はテスト全体が 1 tx で Facade と tx 本体が同じ tx に畳まれるため、本クラスは
 * <b>意図的に {@code @Transactional} を付けず</b>、データをコミットして実際に 2 段を踏ませる。
 * 終了時に自分で作った行を消す。</p>
 *
 * <ul>
 *   <li>K1（AC-17）: 認可が<b>許可で終わった直後</b>（recruitment の {@code *Facade} の中からの呼び出しに限る）に、
 *       対象または親を消す（免除: 記録・募集 / 解除: ペナルティ・発動元設定 / 確定: 参加者・募集 /
 *       ポリシー: ポリシー本体）。tx 本体が「対象→親」をたどり直し、対象の不在コードと同じ 404 を返し、
 *       フック直後から DB が 1 列も変わらないこと。是正前は Facade が無くフックが走らないので赤。</li>
 *   <li>K6（AC-19）: 申込確定の FOR UPDATE（参加者・募集）は認可の後。部外者は別 tx が行ロックを握っていても
 *       待たずに 404、SQL 記録でも FOR UPDATE を 1 本も発行しない。許可された管理者は認可の後に FOR UPDATE で待つ。</li>
 *   <li>AC-12/AC-18: 許可経路で、認可のクエリ（AccessControlService・受取先判定の中で発行された SQL）と、
 *       認可の前（scope 解決）・後（tx 本体）の自ドメインの SELECT を分けて数える。認可のクエリ数は是正前の判定
 *       （{@code isAdminOrAbove} / {@code isPayeeSettlementManager} 単体）と同じ、SYSTEM_ADMIN 判定・在籍判定を
 *       許可経路で足さない、自ドメインの読み直しは認可の後にちょうど 1 巡。</li>
 * </ul>
 */
@AutoConfigureMockMvc(addFilters = false)
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("recruitment 金銭・制裁の認可ファサード型（W4）の競合・ロック順・クエリ回数")
class RecruitmentMoneyFacadeRaceAndQueryIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private RecruitmentCategoryRepository categoryRepository;
    @Autowired
    private RecruitmentListingRepository listingRepository;
    @Autowired
    private RecruitmentParticipantRepository participantRepository;
    @Autowired
    private RecruitmentPenaltySettingRepository penaltySettingRepository;
    @Autowired
    private RecruitmentUserPenaltyRepository penaltyRepository;
    @Autowired
    private RecruitmentCancellationPolicyRepository policyRepository;
    @Autowired
    private RecruitmentCancellationPolicyTierRepository tierRepository;
    @Autowired
    private RecruitmentCancellationRecordRepository recordRepository;
    @Autowired
    private EscrowTransactionRepository escrowTransactionRepository;
    @Autowired
    private ConnectAccountRepository connectAccountRepository;

    /** 認可の「後」に割り込み、認可のクエリを数えるための spy（実処理は必ず呼ぶ）。 */
    @MockitoSpyBean
    private AccessControlService accessControlService;
    /** 免除の許可判定（受取先の精算管理者か）の「後」に割り込み、そのクエリを数えるための spy。 */
    @MockitoSpyBean
    private ConnectChargeService connectChargeService;
    /** 外部境界（Stripe）のみ差し替える。 */
    @MockitoBean
    private com.mannschaft.app.payment.stripe.StripePaymentProvider stripePaymentProvider;

    @PersistenceContext
    private EntityManager em;

    private TransactionTemplate tx;
    private ExecutorService executor;
    private String suffix;

    private Long teamId;
    private Long adminId;
    private Long outsiderId;
    private Long applicantId;
    private Long debtorId;
    private Long payeeId;
    private Long categoryId;
    private Long listingId;
    private Long participantId;
    private Long settingId;
    private Long penaltyId;
    private Long policyId;
    private Long recordId;
    private Long debtorParticipantId;
    private UUID payeeAccountId;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(transactionManager);
        executor = Executors.newCachedThreadPool();
        suffix = Long.toHexString(System.nanoTime());
        tx.executeWithoutResult(status -> {
            teamId = insertTeam("W4RACE-" + suffix);
            adminId = insertUser("w4r-admin-" + suffix + "@example.com");
            outsiderId = insertUser("w4r-outsider-" + suffix + "@example.com");
            applicantId = insertUser("w4r-applicant-" + suffix + "@example.com");
            debtorId = insertUser("w4r-debtor-" + suffix + "@example.com");
            payeeId = insertUser("w4r-payee-" + suffix + "@example.com");
            MembershipTestHelper.insertMembership(em, adminId, ScopeType.TEAM, teamId, RoleKind.MEMBER);
            MembershipTestHelper.insertUserRole(em, adminId, "ADMIN", teamId, null);

            categoryId = categoryRepository.save(RecruitmentCategoryEntity.builder()
                    .code("W4R_" + suffix)
                    .nameI18nKey("recruitment.category.w4race")
                    .defaultParticipationType(RecruitmentParticipationType.INDIVIDUAL)
                    .displayOrder(0)
                    .isActive(true)
                    .build()).getId();
            LocalDateTime start = LocalDateTime.now().plusDays(30);
            listingId = listingRepository.save(RecruitmentListingEntity.builder()
                    .scopeType(RecruitmentScopeType.TEAM)
                    .scopeId(teamId)
                    .categoryId(categoryId)
                    .title("W4RACE 募集")
                    .participationType(RecruitmentParticipationType.INDIVIDUAL)
                    .startAt(start)
                    .endAt(start.plusHours(2))
                    .applicationDeadline(start.minusDays(1))
                    .autoCancelAt(start.minusDays(2))
                    .capacity(10)
                    .minCapacity(1)
                    .status(RecruitmentListingStatus.OPEN)
                    .createdBy(adminId)
                    .build()).getId();
            participantId = participantRepository.save(RecruitmentParticipantEntity.builder()
                    .listingId(listingId)
                    .participantType(RecruitmentParticipantType.USER)
                    .userId(applicantId)
                    .appliedBy(applicantId)
                    .status(RecruitmentParticipantStatus.APPLIED)
                    .build()).getId();
            debtorParticipantId = participantRepository.save(RecruitmentParticipantEntity.builder()
                    .listingId(listingId)
                    .participantType(RecruitmentParticipantType.USER)
                    .userId(debtorId)
                    .appliedBy(debtorId)
                    .status(RecruitmentParticipantStatus.CANCELLED)
                    .build()).getId();

            settingId = penaltySettingRepository.save(RecruitmentPenaltySettingEntity.builder()
                    .scopeType(RecruitmentScopeType.TEAM).scopeId(teamId).build()).getId();
            penaltyId = penaltyRepository.save(RecruitmentUserPenaltyEntity.builder()
                    .userId(applicantId)
                    .scopeType(RecruitmentScopeType.TEAM)
                    .scopeId(teamId)
                    .triggeredBySettingId(settingId)
                    .triggeredNoShowCount(3)
                    .startedAt(LocalDateTime.now().minusDays(1))
                    .expiresAt(LocalDateTime.now().plusDays(30))
                    .build()).getId();

            policyId = policyRepository.save(RecruitmentCancellationPolicyEntity.builder()
                    .scopeType(RecruitmentScopeType.TEAM)
                    .scopeId(teamId)
                    .policyName("W4RACE ポリシー")
                    .freeUntilHoursBefore(48)
                    .isTemplatePolicy(true)
                    .createdBy(adminId)
                    .build()).getId();
            tierRepository.save(RecruitmentCancellationPolicyTierEntity.builder()
                    .policyId(policyId)
                    .tierOrder(1)
                    .appliesAtOrBeforeHours(24)
                    .feeType(CancellationFeeType.PERCENTAGE)
                    .feeValue(50)
                    .build());

            payeeAccountId = connectAccountRepository.save(ConnectAccountEntity.builder()
                    .scopeKind(ScopeKind.USER)
                    .scopeId(payeeId)
                    .stripeAccountId("acct_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16))
                    .onboardingStatus(OnboardingStatus.READY)
                    .chargesEnabled(true)
                    .payoutsEnabled(true)
                    .build()).getId();
            recordId = recordRepository.save(RecruitmentCancellationRecordEntity.builder()
                    .participantId(debtorParticipantId)
                    .listingId(listingId)
                    .userId(debtorId)
                    .teamId(null)
                    .cancelledAt(LocalDateTime.now())
                    .cancelledBy(debtorId)
                    .cancelSource(CancellationSource.USER)
                    .hoursBeforeStart(6)
                    .feeAmount(3_000)
                    .paymentStatus(CancellationPaymentStatus.PENDING)
                    .build()).getId();
            escrowTransactionRepository.save(EscrowTransactionEntity.builder()
                    .sourceKind(EscrowSourceKind.RECRUITMENT)
                    .sourceId(listingId)
                    .sourceParticipantId(debtorParticipantId)
                    .captureMode(EscrowCaptureMode.MANUAL)
                    .payerScopeKind(ScopeKind.USER)
                    .payerScopeId(debtorId)
                    .payeeKind(ScopeKind.USER)
                    .payeeConnectAccountId(payeeAccountId)
                    .faceAmount(10_000L)
                    .amount(10_250L)
                    .applicationFeeAmount(250L)
                    .currency("JPY")
                    .feePolicyKey("RECRUITMENT_DEFAULT")
                    .status(EscrowStatus.AUTHORIZED)
                    .build());
        });
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
        SecurityContextHolder.clearContext();
        tx.executeWithoutResult(status -> {
            exec("DELETE FROM escrow_transactions WHERE source_id = :id", listingId);
            exec("DELETE FROM recruitment_cancellation_records WHERE listing_id = :id", listingId);
            exec("DELETE FROM recruitment_reminders WHERE listing_id = :id", listingId);
            exec("DELETE FROM recruitment_participant_history WHERE listing_id = :id", listingId);
            exec("DELETE FROM recruitment_participants WHERE listing_id = :id", listingId);
            exec("DELETE FROM recruitment_listings WHERE id = :id", listingId);
            exec("DELETE FROM recruitment_categories WHERE id = :id", categoryId);
            exec("DELETE FROM recruitment_user_penalties WHERE triggered_by_setting_id = :id", settingId);
            exec("DELETE FROM recruitment_penalty_settings WHERE id = :id", settingId);
            exec("DELETE FROM recruitment_cancellation_policy_tiers WHERE policy_id = :id", policyId);
            exec("DELETE FROM recruitment_cancellation_policies WHERE id = :id", policyId);
            em.createNativeQuery("DELETE FROM connect_accounts WHERE scope_kind = 'USER' AND scope_id = :id")
                    .setParameter("id", payeeId).executeUpdate();
            exec("DELETE FROM user_roles WHERE team_id = :id", teamId);
            exec("DELETE FROM memberships WHERE scope_type = 'TEAM' AND scope_id = :id", teamId);
            em.createNativeQuery("DELETE FROM users WHERE email LIKE :p").setParameter("p", "w4r-%-" + suffix + "@%")
                    .executeUpdate();
            exec("DELETE FROM teams WHERE id = :id", teamId);
        });
    }

    private void exec(String sql, Object id) {
        em.createNativeQuery(sql).setParameter("id", id).executeUpdate();
    }

    // ═════════════════════════════════════════════════════════════════════
    // K1 / AC-17: 認可の後・tx の前に対象や親が消えたら、対象の不在コードの 404・DB 不変
    // ═════════════════════════════════════════════════════════════════════

    /** どの許可判定の「直後」に割り込むか。 */
    private enum Hook {
        /** 管理者判定（{@code isAdminOrAbove} が true を返した直後）。 */
        ADMIN,
        /** 免除の受取先判定（{@code isPayeeSettlementManager} が true を返した直後）。 */
        PAYEE
    }

    private enum RaceCase {
        WAIVE_RECORD_DELETED("免除: 記録が論理削除された", Hook.PAYEE, CommonErrorCode.COMMON_005,
                "UPDATE recruitment_cancellation_records SET deleted_at = NOW() WHERE id = :record"),
        WAIVE_LISTING_DELETED("免除: 親の募集が論理削除された", Hook.PAYEE, CommonErrorCode.COMMON_005,
                "UPDATE recruitment_listings SET deleted_at = NOW() WHERE id = :listing"),
        LIFT_PENALTY_DELETED("解除: ペナルティが削除された", Hook.ADMIN, RecruitmentErrorCode.PENALTY_NOT_FOUND,
                "DELETE FROM recruitment_user_penalties WHERE id = :penalty"),
        LIFT_SETTING_DELETED("解除: 親の発動元設定が削除された", Hook.ADMIN, RecruitmentErrorCode.PENALTY_NOT_FOUND,
                "DELETE FROM recruitment_penalty_settings WHERE id = :setting"),
        CONFIRM_PARTICIPANT_DELETED("確定: 参加者が削除された", Hook.ADMIN, RecruitmentErrorCode.LISTING_NOT_FOUND,
                "DELETE FROM recruitment_participants WHERE id = :participant"),
        CONFIRM_LISTING_DELETED("確定: 親の募集が論理削除された", Hook.ADMIN, RecruitmentErrorCode.LISTING_NOT_FOUND,
                "UPDATE recruitment_listings SET deleted_at = NOW() WHERE id = :listing"),
        POLICY_GET_DELETED("ポリシー詳細: 論理削除された", Hook.ADMIN, RecruitmentErrorCode.LISTING_NOT_FOUND,
                "UPDATE recruitment_cancellation_policies SET deleted_at = NOW() WHERE id = :policy"),
        POLICY_PATCH_DELETED("ポリシー編集: 論理削除された", Hook.ADMIN, RecruitmentErrorCode.LISTING_NOT_FOUND,
                "UPDATE recruitment_cancellation_policies SET deleted_at = NOW() WHERE id = :policy"),
        POLICY_ARCHIVE_DELETED("ポリシー論理削除: 先に論理削除された", Hook.ADMIN, RecruitmentErrorCode.LISTING_NOT_FOUND,
                "UPDATE recruitment_cancellation_policies SET deleted_at = NOW() WHERE id = :policy");

        final String label;
        final Hook hook;
        final ErrorCode expected;
        final String deleteSql;

        RaceCase(String label, Hook hook, ErrorCode expected, String deleteSql) {
            this.label = label;
            this.hook = hook;
            this.expected = expected;
            this.deleteSql = deleteSql;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private MockHttpServletRequestBuilder raceRequest(RaceCase c) {
        return switch (c) {
            case WAIVE_RECORD_DELETED, WAIVE_LISTING_DELETED -> waiveRequest();
            case LIFT_PENALTY_DELETED, LIFT_SETTING_DELETED -> liftRequest();
            case CONFIRM_PARTICIPANT_DELETED, CONFIRM_LISTING_DELETED -> confirmRequest();
            case POLICY_GET_DELETED -> get("/api/v1/cancellation-policies/{id}", policyId);
            case POLICY_PATCH_DELETED -> policyPatchRequest();
            case POLICY_ARCHIVE_DELETED -> post("/api/v1/cancellation-policies/{id}/archive", policyId);
        };
    }

    @ParameterizedTest(name = "K1: {0}")
    @EnumSource(RaceCase.class)
    @DisplayName("K1: 認可の後・tx の前に対象や親が消えたら、対象の不在コードと同じ 404 になり DB は変わらない")
    void 認可後に対象や親が消えたら404でDB不変(RaceCase c) throws Exception {
        AtomicBoolean fired = new AtomicBoolean(false);
        AtomicReference<String> afterHook = new AtomicReference<>();
        Runnable deleter = () -> {
            runInNewTx(c.deleteSql);
            afterHook.set(snapshot());
        };
        Answer<Object> hook = inv -> {
            Object result = inv.callRealMethod();
            if (Boolean.TRUE.equals(result) && calledFromRecruitmentFacade() && fired.compareAndSet(false, true)) {
                deleter.run();
            }
            return result;
        };
        if (c.hook == Hook.PAYEE) {
            Mockito.doAnswer(hook).when(connectChargeService).isPayeeSettlementManager(any(), any(), any(), any());
        } else {
            Mockito.doAnswer(hook).when(accessControlService).isAdminOrAbove(any(), any(), any());
        }

        Long actor = c.hook == Hook.PAYEE ? payeeId : adminId;
        setAuth(actor);
        MvcResult result = mockMvc.perform(raceRequest(c)).andReturn();

        // フックが走らなければ 404 の根拠が崩れるので先に見る（是正前は Facade が無く、ここで落ちる）。
        assertThat(fired.get()).as("recruitment の Facade の中で許可判定が終わった直後にフックが走ること").isTrue();
        assertNotFound(result, c.expected);
        assertThat(snapshot()).as("フックで消した直後から、記録・ペナルティ・参加者・募集・ポリシー・tier は 1 列も変わらない")
                .isEqualTo(afterHook.get());
    }

    /** 呼び出しが recruitment の {@code *Facade}（tx の外の認可層）の中からか。 */
    private static boolean calledFromRecruitmentFacade() {
        return Arrays.stream(Thread.currentThread().getStackTrace())
                .anyMatch(e -> e.getClassName().startsWith("com.mannschaft.app.recruitment.")
                        && e.getClassName().endsWith("Facade"));
    }

    // ═════════════════════════════════════════════════════════════════════
    // K6 / AC-19: 申込確定の FOR UPDATE は認可の後
    // ═════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("K6: 別 tx が参加者・募集の行を FOR UPDATE で握っていても、部外者は待たずに不在と同一の 404")
    void 部外者は行ロックを待たずに404() throws Exception {
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Void> holder = holdConfirmLocks(locked, release);
        try {
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            // 部外者が FOR UPDATE を取りに行けば、保持中の行ロックに塞がれて 8 秒以内に返らない。
            MvcResult result = assertTimeoutPreemptively(Duration.ofSeconds(8), () -> {
                setAuth(outsiderId);
                return mockMvc.perform(confirmRequest()).andReturn();
            });
            assertNotFound(result, RecruitmentErrorCode.LISTING_NOT_FOUND);
        } finally {
            release.countDown();
            holder.get(60, TimeUnit.SECONDS);
        }
    }

    @Test
    @DisplayName("K6: 部外者の確定要求は FOR UPDATE を 1 本も発行しない（SQL 記録）")
    void 部外者はFOR_UPDATEを発行しない() throws Exception {
        setAuth(outsiderId);
        SqlIntentCounter.reset();
        MvcResult result = mockMvc.perform(confirmRequest()).andReturn();
        List<String> sqls = SqlIntentCounter.capturedSqls();
        assertThat(sqls).as("SQL 記録が有効であること（StatementInspector の登録）").isNotEmpty();
        assertThat(sqls.stream().filter(RecruitmentMoneyFacadeRaceAndQueryIT::isForUpdate).toList())
                .as("拒否経路で行ロックを取らない").isEmpty();
        assertNotFound(result, RecruitmentErrorCode.LISTING_NOT_FOUND);
    }

    @Test
    @DisplayName("K6: 許可された管理者は認可の後に FOR UPDATE へ進む（FOR UPDATE の中で塞がれ、解放後に 200）")
    void 管理者は認可の後に行ロックを取りに行く() throws Exception {
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Void> holder = holdConfirmLocks(locked, release);
        try {
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            AtomicReference<Thread> requestThread = new AtomicReference<>();
            CompletableFuture<Integer> response = CompletableFuture.supplyAsync(() -> {
                requestThread.set(Thread.currentThread());
                try {
                    setAuth(adminId);
                    return mockMvc.perform(confirmRequest()).andReturn().getResponse().getStatus();
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                } finally {
                    SecurityContextHolder.clearContext();
                }
            }, executor);
            Awaitility.await().atMost(20, TimeUnit.SECONDS).pollInterval(Duration.ofMillis(20))
                    .until(() -> response.isDone() || isInsideFindByIdForUpdate(requestThread.get()));
            assertThat(response.isDone()).as("ロック保持中に管理者の要求が完了してはならない").isFalse();
            assertThat(isInsideFindByIdForUpdate(requestThread.get())).isTrue();
            release.countDown();
            assertThat(response.get(30, TimeUnit.SECONDS)).isEqualTo(200);
        } finally {
            release.countDown();
            holder.get(60, TimeUnit.SECONDS);
        }
    }

    private static boolean isInsideFindByIdForUpdate(Thread thread) {
        return thread != null && Arrays.stream(thread.getStackTrace())
                .anyMatch(e -> "findByIdForUpdate".equals(e.getMethodName()));
    }

    private CompletableFuture<Void> holdConfirmLocks(CountDownLatch locked, CountDownLatch release) {
        return CompletableFuture.runAsync(() -> tx.executeWithoutResult(status -> {
            participantRepository.findByIdForUpdate(participantId).orElseThrow();
            listingRepository.findByIdForUpdate(listingId).orElseThrow();
            locked.countDown();
            try {
                release.await(40, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }), executor);
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-12 / AC-18: 許可経路のクエリ回数（認可 / scope 解決 / tx 本体を分けて数える）
    // ═════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("AC-18: ペナルティ解除（管理者）— 認可クエリは是正前と同数、ペナルティ・設定の読み直しは認可の後にちょうど 1 巡")
    void 解除_許可経路のクエリ回数() throws Exception {
        long baseline = adminCheckBaseline();
        Measured m = measure(adminId, liftRequest());
        m.print("lift");
        assertThat(m.status).isEqualTo(200);
        assertAuthzNotIncreased(m, baseline);
        assertSelects(m, "recruitment_user_penalties", 1, 1);
        assertSelects(m, "recruitment_penalty_settings", 1, 1);
    }

    @Test
    @DisplayName("AC-18/AC-19: 申込確定（管理者）— 認可クエリは是正前と同数、FOR UPDATE は認可の後にだけ 1 本ずつ、認可の前は素の読み取り 1 本ずつ")
    void 確定_許可経路のクエリ回数() throws Exception {
        long baseline = adminCheckBaseline();
        Measured m = measure(adminId, confirmRequest());
        m.print("confirm");
        assertThat(m.status).isEqualTo(200);
        assertAuthzNotIncreased(m, baseline);
        for (String table : List.of("recruitment_participants", "recruitment_listings")) {
            assertThat(m.count(m.beforeAuthz(), table, true)).as(table + " 認可の前の FOR UPDATE").isZero();
            assertThat(m.count(m.afterAuthz(), table, true)).as(table + " 認可の後の FOR UPDATE").isEqualTo(1);
            assertThat(m.count(m.beforeAuthz(), table, false)).as(table + " 認可の前の素の読み取り（scope 解決）")
                    .isEqualTo(1);
        }
    }

    @Test
    @DisplayName("AC-18: ポリシー詳細・編集・論理削除（管理者）— 認可クエリは是正前と同数、ポリシーの読み直しは認可の後にちょうど 1 巡")
    void ポリシー_許可経路のクエリ回数() throws Exception {
        long baseline = adminCheckBaseline();
        for (MockHttpServletRequestBuilder request : List.of(
                get("/api/v1/cancellation-policies/{id}", policyId),
                policyPatchRequest(),
                post("/api/v1/cancellation-policies/{id}/archive", policyId))) {
            Measured m = measure(adminId, request);
            m.print("policy");
            assertThat(m.status).isBetween(200, 204);
            assertAuthzNotIncreased(m, baseline);
            assertSelects(m, "recruitment_cancellation_policies", 1, 1);
        }
    }

    @Test
    @DisplayName("AC-18: 免除（受取本人）— 受取先判定のクエリは是正前と同数、SYSTEM_ADMIN 判定を足さない、記録の読み直しは認可の後に 1 巡、募集は読まない")
    void 免除_許可経路のクエリ回数() throws Exception {
        // 是正前の判定（受取先の精算管理者か）の単体のクエリ数。初回コストを外すため 1 度流してから測る。
        connectChargeService.isPayeeSettlementManager(EscrowSourceKind.RECRUITMENT, listingId, debtorParticipantId,
                payeeId);
        SqlIntentCounter.reset();
        connectChargeService.isPayeeSettlementManager(EscrowSourceKind.RECRUITMENT, listingId, debtorParticipantId,
                payeeId);
        long baseline = SqlIntentCounter.totalCount();
        Mockito.clearInvocations(connectChargeService, accessControlService);

        Measured m = measure(payeeId, waiveRequest());
        m.print("waive");
        assertThat(m.status).isEqualTo(200);
        assertThat(m.authzSql).as("認可（受取先判定）のクエリ数は是正前の判定単体と同じ").isEqualTo(baseline);
        Mockito.verify(accessControlService, never()).isSystemAdmin(any());
        Mockito.verify(accessControlService, never()).isMember(any(), any(), any());
        assertSelects(m, "recruitment_cancellation_records", 1, 1);
        assertThat(m.count(m.sqls, "recruitment_listings", false))
                .as("許可経路では募集を読まない（募集のスコープは拒否経路でだけ使う）").isZero();
    }

    /** 是正前の管理者判定（{@code isAdminOrAbove} 単体）のクエリ数。初回コストを外すため 1 度流してから測る。 */
    private long adminCheckBaseline() {
        accessControlService.isAdminOrAbove(adminId, teamId, "TEAM");
        SqlIntentCounter.reset();
        accessControlService.isAdminOrAbove(adminId, teamId, "TEAM");
        long baseline = SqlIntentCounter.totalCount();
        assertThat(baseline).as("SQL 記録が有効であること").isPositive();
        Mockito.clearInvocations(accessControlService, connectChargeService);
        return baseline;
    }

    private void assertAuthzNotIncreased(Measured m, long baseline) {
        assertThat(m.authzSql).as("認可のクエリ数は是正前（isAdminOrAbove 単体）と同じ").isEqualTo(baseline);
        // SYSTEM_ADMIN は是正前どおり通さない（裁可 2026-09-30）ので、許可経路で SYSTEM_ADMIN 判定を足さない。
        Mockito.verify(accessControlService, never()).isSystemAdmin(any());
        // 在籍判定は拒否経路だけ（許可経路では足さない）。
        Mockito.verify(accessControlService, never()).isMember(any(), any(), any());
    }

    private void assertSelects(Measured m, String table, int beforeAuthz, int afterAuthz) {
        assertThat(m.count(m.beforeAuthz(), table, false)).as(table + " 認可の前の SELECT（scope 解決）")
                .isEqualTo(beforeAuthz);
        assertThat(m.count(m.afterAuthz(), table, false)).as(table + " 認可の後の SELECT（tx 本体の読み直し）")
                .isEqualTo(afterAuthz);
    }

    /** 1 リクエストの計測結果。認可呼び出し（最外側）の区間で、SQL 記録を「前・中・後」に分ける。 */
    private static final class Measured {
        int status;
        List<String> sqls = List.of();
        int firstAuthzStart = -1;
        int lastAuthzEnd = -1;
        long authzSql;

        List<String> beforeAuthz() {
            return firstAuthzStart < 0 ? sqls : sqls.subList(0, Math.min(firstAuthzStart, sqls.size()));
        }

        List<String> afterAuthz() {
            return lastAuthzEnd < 0 ? List.of() : sqls.subList(Math.min(lastAuthzEnd, sqls.size()), sqls.size());
        }

        long count(List<String> list, String table, boolean forUpdate) {
            return list.stream().filter(s -> isSelectFrom(s, table) && isForUpdate(s) == forUpdate).count();
        }

        void print(String label) {
            System.out.printf("[W4 AC-18] %s status=%d authzSql=%d firstAuthzStart=%d lastAuthzEnd=%d total=%d%n%s%n",
                    label, status, authzSql, firstAuthzStart, lastAuthzEnd, sqls.size(),
                    sqls.stream().map(s -> "  " + s).collect(Collectors.joining("\n")));
        }
    }

    /**
     * 認可の呼び出し（AccessControlService の判定・受取先判定）を包み、その中で発行された SQL を数えながら
     * リクエストを流す。入れ子（checkAdminOrAbove → isAdminOrAbove 等）は最外側だけを数える。
     */
    private Measured measure(Long actor, MockHttpServletRequestBuilder request) throws Exception {
        Measured m = new Measured();
        ThreadLocal<Integer> depth = ThreadLocal.withInitial(() -> 0);
        Answer<Object> wrap = inv -> {
            int d = depth.get();
            depth.set(d + 1);
            int start = SqlIntentCounter.totalCount();
            if (d == 0 && m.firstAuthzStart < 0) {
                m.firstAuthzStart = start;
            }
            try {
                return inv.callRealMethod();
            } finally {
                depth.set(d);
                if (d == 0) {
                    int end = SqlIntentCounter.totalCount();
                    m.authzSql += end - start;
                    m.lastAuthzEnd = end;
                }
            }
        };
        Mockito.doAnswer(wrap).when(accessControlService).isSystemAdmin(any());
        Mockito.doAnswer(wrap).when(accessControlService).isAdminOrAbove(any(), any(), any());
        Mockito.doAnswer(wrap).when(accessControlService).isMember(any(), any(), any());
        Mockito.doAnswer(wrap).when(accessControlService).isSupporter(any(), any(), any());
        Mockito.doAnswer(wrap).when(accessControlService).checkAdminOrAbove(any(), any(), any());
        Mockito.doAnswer(wrap).when(accessControlService).checkMembership(any(), any(), any());
        Mockito.doAnswer(wrap).when(connectChargeService).isPayeeSettlementManager(any(), any(), any(), any());
        Mockito.clearInvocations(accessControlService, connectChargeService);

        setAuth(actor);
        SqlIntentCounter.reset();
        MvcResult result = mockMvc.perform(request).andReturn();
        m.sqls = new ArrayList<>(SqlIntentCounter.capturedSqls());
        m.status = result.getResponse().getStatus();
        assertThat(m.sqls).as("SQL 記録が有効であること").isNotEmpty();
        assertThat(m.firstAuthzStart).as("認可の呼び出しがあること").isNotNegative();
        return m;
    }

    private static boolean isSelectFrom(String sql, String table) {
        String s = sql.toLowerCase();
        return s.stripLeading().startsWith("select")
                && (s.contains(" from " + table + " ") || s.contains(" join " + table + " "));
    }

    private static boolean isForUpdate(String sql) {
        return sql.toLowerCase().contains(" for update");
    }

    // ═════════════════════════════════════════════════════════════════════
    // ヘルパー
    // ═════════════════════════════════════════════════════════════════════

    private MockHttpServletRequestBuilder waiveRequest() {
        return postJson("/api/v1/recruitment-cancellation-records/" + recordId + "/waive",
                Map.of("reason", "W4 競合テスト"));
    }

    private MockHttpServletRequestBuilder liftRequest() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("liftReason", PenaltyLiftReason.ADMIN_MANUAL.name());
        body.put("liftNote", "W4 競合テスト");
        return postJson("/api/v1/scopes/TEAM/" + teamId + "/penalties/" + penaltyId + "/lift", body);
    }

    private MockHttpServletRequestBuilder confirmRequest() {
        return post("/api/v1/recruitment-listings/{listingId}/participants/{participantId}/confirm",
                listingId, participantId);
    }

    private MockHttpServletRequestBuilder policyPatchRequest() {
        Map<String, Object> tier = new LinkedHashMap<>();
        tier.put("tierOrder", 1);
        tier.put("appliesAtOrBeforeHours", 12);
        tier.put("feeType", CancellationFeeType.FIXED.name());
        tier.put("feeValue", 500);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("policyName", "W4 編集後");
        body.put("tiers", List.of(tier));
        return patch("/api/v1/cancellation-policies/{id}", policyId)
                .contentType(MediaType.APPLICATION_JSON).content(toJson(body));
    }

    private MockHttpServletRequestBuilder postJson(String url, Object body) {
        return post(url).contentType(MediaType.APPLICATION_JSON).content(toJson(body));
    }

    private String toJson(Object body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private void assertNotFound(MvcResult result, ErrorCode expected) throws Exception {
        String content = result.getResponse().getContentAsString();
        assertThat(result.getResponse().getStatus()).as(content).isEqualTo(404);
        assertThat((String) JsonPath.read(content, "$.error.code")).isEqualTo(expected.getCode());
        assertThat((String) JsonPath.read(content, "$.error.message")).isEqualTo(expected.getMessage());
    }

    /**
     * フックは認可（Gate / 受取先判定の readOnly tx）の呼び出しの中で走るため、既存 tx に合流させず
     * {@code REQUIRES_NEW} の別 tx で書いてコミットする。
     */
    private void runInNewTx(String sql) {
        TransactionTemplate requiresNew = new TransactionTemplate(transactionManager);
        requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        requiresNew.executeWithoutResult(s -> {
            var q = em.createNativeQuery(sql);
            Map<String, Object> params = Map.of("record", recordId, "listing", listingId, "penalty", penaltyId,
                    "setting", settingId, "participant", participantId, "policy", policyId);
            params.forEach((k, v) -> {
                if (sql.contains(":" + k)) {
                    q.setParameter(k, v);
                }
            });
            q.executeUpdate();
        });
    }

    /** 対象と親の行を全列（更新時刻を含む）そのまま文字列にして連結する。 */
    private String snapshot() {
        TransactionTemplate requiresNew = new TransactionTemplate(transactionManager);
        requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return requiresNew.execute(s -> Stream.of(
                        "SELECT * FROM recruitment_cancellation_records WHERE listing_id = " + listingId + " ORDER BY id",
                        "SELECT * FROM recruitment_user_penalties WHERE triggered_by_setting_id = " + settingId
                                + " ORDER BY id",
                        "SELECT * FROM recruitment_penalty_settings WHERE id = " + settingId,
                        "SELECT * FROM recruitment_participants WHERE listing_id = " + listingId + " ORDER BY id",
                        "SELECT * FROM recruitment_participant_history WHERE listing_id = " + listingId + " ORDER BY id",
                        "SELECT * FROM recruitment_listings WHERE id = " + listingId,
                        "SELECT * FROM recruitment_cancellation_policies WHERE id = " + policyId,
                        "SELECT * FROM recruitment_cancellation_policy_tiers WHERE policy_id = " + policyId
                                + " ORDER BY id")
                .map(sql -> {
                    @SuppressWarnings("unchecked")
                    List<Object> rows = em.createNativeQuery(sql).getResultList();
                    return rows.stream()
                            .map(r -> r instanceof Object[] cols ? Arrays.deepToString(cols) : String.valueOf(r))
                            .collect(Collectors.joining(",", "[", "]"));
                })
                .collect(Collectors.joining("\n")));
    }

    private void setAuth(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
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
                                + "VALUES (:email, 'W4RACE', 'テスト', 'W4RACE テスト', 'ACTIVE', "
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
                                + "CONCAT('w4r-', LEFT(REPLACE(UUID(),'-',''),8)), NOW(), NOW())")
                .setParameter("name", name)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM teams WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult()).longValue();
    }
}
