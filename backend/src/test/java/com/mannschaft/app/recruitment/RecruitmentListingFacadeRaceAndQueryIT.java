package com.mannschaft.app.recruitment;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.ErrorCode;
import com.mannschaft.app.common.visibility.perf.SqlIntentCounter;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.payment.escrow.ConnectChargeService;
import com.mannschaft.app.recruitment.entity.RecruitmentCategoryEntity;
import com.mannschaft.app.recruitment.entity.RecruitmentDistributionTargetEntity;
import com.mannschaft.app.recruitment.entity.RecruitmentListingEntity;
import com.mannschaft.app.recruitment.entity.RecruitmentNoShowRecordEntity;
import com.mannschaft.app.recruitment.entity.RecruitmentParticipantEntity;
import com.mannschaft.app.recruitment.entity.RecruitmentTemplateEntity;
import com.mannschaft.app.recruitment.repository.RecruitmentCategoryRepository;
import com.mannschaft.app.recruitment.repository.RecruitmentDistributionTargetRepository;
import com.mannschaft.app.recruitment.repository.RecruitmentListingRepository;
import com.mannschaft.app.recruitment.repository.RecruitmentNoShowRecordRepository;
import com.mannschaft.app.recruitment.repository.RecruitmentParticipantRepository;
import com.mannschaft.app.recruitment.repository.RecruitmentTemplateRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.MembershipTestHelper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
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

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * recruitment の募集書込系・テンプレートを「認可（tx の外の Facade）→ tx 本体」に分けた型
 * （CMP-260923-0954 W5 / plan4）の<b>競合・ロック順・クエリ回数</b>の契約テスト（試練＝実装前の red）。
 *
 * <p>{@code *ScopeContractIT} はテスト全体が 1 tx で Facade と tx 本体が同じ tx に畳まれるため、本クラスは
 * <b>意図的に {@code @Transactional} を付けず</b>、データをコミットして実際に 2 段を踏ませる。
 * 終了時に自分で作った行を消す。W4 の {@code RecruitmentMoneyFacadeRaceAndQueryIT} と同じ Bean 差し替え構成にして
 * Spring コンテキストを共有する。</p>
 *
 * <ul>
 *   <li>K1（AC-17）: 認可が<b>許可で終わった直後</b>（recruitment の {@code *Facade} の中からの呼び出しに限る）に、
 *       対象または親（募集・参加者・テンプレート）を消す。tx 本体が「対象→親（募集）」をたどり直し、
 *       対象の不在コードと同じ 404 を返し、フック直後から DB が 1 列も変わらないこと（チーム・組織は見ない）。
 *       是正前は Facade が無くフックが走らないので赤。</li>
 *   <li>K6（AC-19）: FOR UPDATE を使う EP（募集の PATCH・publish・cancel・archive、申込、no-show の本人申立）で、
 *       部外者の要求は SQL 記録上 FOR UPDATE を 1 本も発行しない。許可された管理者は認可の後にだけ FOR UPDATE を取る。</li>
 *   <li>AC-12/AC-18: 許可経路で、認可のクエリ（AccessControlService の中で発行された SQL）と、
 *       認可の前（scope 解決）・後（tx 本体）の自ドメインの SELECT を分けて数える。認可のクエリ数は是正前の判定単体
 *       （{@code isAdminOrAbove} / {@code isMember}）と同じ、SYSTEM_ADMIN 判定を許可経路で足さない。
 *       C6: 参加者一覧は 0・1・複数件で認可のクエリ数と参加者の SELECT 数が変わらない。</li>
 * </ul>
 */
@AutoConfigureMockMvc(addFilters = false)
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("recruitment 募集・テンプレートの認可ファサード型（W5）の競合・ロック順・クエリ回数")
class RecruitmentListingFacadeRaceAndQueryIT extends AbstractMySqlIntegrationTest {

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
    private RecruitmentDistributionTargetRepository distributionTargetRepository;
    @Autowired
    private RecruitmentTemplateRepository templateRepository;
    @Autowired
    private RecruitmentNoShowRecordRepository noShowRepository;

    /** 認可の「後」に割り込み、認可のクエリを数えるための spy（実処理は必ず呼ぶ）。 */
    @MockitoSpyBean
    private AccessControlService accessControlService;
    /** W4 の競合 IT と同じ差し替え構成にしてコンテキストを共有するための spy（本クラスでは使わない）。 */
    @MockitoSpyBean
    private ConnectChargeService connectChargeService;
    /** 外部境界（Stripe）のみ差し替える。 */
    @MockitoBean
    private com.mannschaft.app.payment.stripe.StripePaymentProvider stripePaymentProvider;

    @PersistenceContext
    private EntityManager em;

    private TransactionTemplate tx;
    private String suffix;

    private Long teamId;
    private Long adminId;
    private Long memberId;
    private Long outsiderId;
    private Long categoryId;
    private Long listingId;
    private Long draftId;
    private Long attendParticipantId;
    private Long templateId;
    private Long noShowId;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(transactionManager);
        suffix = Long.toHexString(System.nanoTime());
        tx.executeWithoutResult(status -> {
            teamId = insertTeam("W5RACE-" + suffix);
            adminId = insertUser("w5r-admin-" + suffix + "@example.com");
            memberId = insertUser("w5r-member-" + suffix + "@example.com");
            outsiderId = insertUser("w5r-outsider-" + suffix + "@example.com");
            MembershipTestHelper.insertMembership(em, adminId, ScopeType.TEAM, teamId, RoleKind.MEMBER);
            MembershipTestHelper.insertUserRole(em, adminId, "ADMIN", teamId, null);
            MembershipTestHelper.insertMembership(em, memberId, ScopeType.TEAM, teamId, RoleKind.MEMBER);

            categoryId = categoryRepository.save(RecruitmentCategoryEntity.builder()
                    .code("W5R_" + suffix)
                    .nameI18nKey("recruitment.category.w5race")
                    .defaultParticipationType(RecruitmentParticipationType.INDIVIDUAL)
                    .displayOrder(0)
                    .isActive(true)
                    .build()).getId();
            listingId = insertListing(RecruitmentListingStatus.OPEN);
            draftId = insertListing(RecruitmentListingStatus.DRAFT);
            for (Long id : List.of(listingId, draftId)) {
                distributionTargetRepository.save(RecruitmentDistributionTargetEntity.builder()
                        .listingId(id)
                        .targetType(RecruitmentDistributionTargetType.MEMBERS)
                        .build());
            }
            attendParticipantId = insertParticipant(listingId, memberId, RecruitmentParticipantStatus.CONFIRMED);
            Long noShowParticipantId = insertParticipant(listingId, memberId, RecruitmentParticipantStatus.NO_SHOW);
            noShowId = noShowRepository.save(RecruitmentNoShowRecordEntity.builder()
                    .participantId(noShowParticipantId)
                    .listingId(listingId)
                    .userId(memberId)
                    .reason(NoShowReason.ADMIN_MARKED)
                    .recordedBy(adminId)
                    .build()).getId();
            templateId = templateRepository.save(RecruitmentTemplateEntity.builder()
                    .scopeType(RecruitmentScopeType.TEAM)
                    .scopeId(teamId)
                    .categoryId(categoryId)
                    .templateName("W5RACE テンプレート")
                    .title("W5RACE テンプレート募集")
                    .defaultLocation("W5RACE 会場")
                    .createdBy(adminId)
                    .build()).getId();
        });
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        tx.executeWithoutResult(status -> {
            String listings = "(SELECT id FROM (SELECT id FROM recruitment_listings WHERE category_id = :id) x)";
            exec("DELETE FROM recruitment_no_show_records WHERE listing_id IN " + listings, categoryId);
            exec("DELETE FROM recruitment_reminders WHERE listing_id IN " + listings, categoryId);
            exec("DELETE FROM recruitment_participant_history WHERE listing_id IN " + listings, categoryId);
            exec("DELETE FROM recruitment_participants WHERE listing_id IN " + listings, categoryId);
            exec("DELETE FROM recruitment_distribution_targets WHERE listing_id IN " + listings, categoryId);
            exec("DELETE FROM recruitment_listings WHERE category_id = :id", categoryId);
            exec("DELETE FROM recruitment_templates WHERE category_id = :id", categoryId);
            exec("DELETE FROM recruitment_categories WHERE id = :id", categoryId);
            exec("DELETE FROM user_roles WHERE team_id = :id", teamId);
            exec("DELETE FROM memberships WHERE scope_type = 'TEAM' AND scope_id = :id", teamId);
            em.createNativeQuery("DELETE FROM users WHERE email LIKE :p").setParameter("p", "w5r-%-" + suffix + "@%")
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
        /** 在籍判定（{@code isMember} が true を返した直後。テンプレート詳細）。 */
        MEMBER
    }

    private enum RaceCase {
        UPDATE_LISTING_DELETED("募集編集: 募集が論理削除された", Hook.ADMIN, RecruitmentErrorCode.LISTING_NOT_FOUND,
                "UPDATE recruitment_listings SET deleted_at = NOW() WHERE id = :listing"),
        PUBLISH_LISTING_DELETED("公開: 募集が論理削除された", Hook.ADMIN, RecruitmentErrorCode.LISTING_NOT_FOUND,
                "UPDATE recruitment_listings SET deleted_at = NOW() WHERE id = :draft"),
        CANCEL_LISTING_DELETED("中止: 募集が論理削除された", Hook.ADMIN, RecruitmentErrorCode.LISTING_NOT_FOUND,
                "UPDATE recruitment_listings SET deleted_at = NOW() WHERE id = :listing"),
        ARCHIVE_LISTING_DELETED("論理削除: 先に論理削除された", Hook.ADMIN, RecruitmentErrorCode.LISTING_NOT_FOUND,
                "UPDATE recruitment_listings SET deleted_at = NOW() WHERE id = :listing"),
        DIST_GET_LISTING_DELETED("配信対象取得: 募集が論理削除された", Hook.ADMIN,
                RecruitmentErrorCode.LISTING_NOT_FOUND,
                "UPDATE recruitment_listings SET deleted_at = NOW() WHERE id = :listing"),
        DIST_PUT_LISTING_DELETED("配信対象設定: 募集が論理削除された", Hook.ADMIN,
                RecruitmentErrorCode.LISTING_NOT_FOUND,
                "UPDATE recruitment_listings SET deleted_at = NOW() WHERE id = :listing"),
        PARTICIPANTS_LISTING_DELETED("参加者一覧: 募集が論理削除された", Hook.ADMIN,
                RecruitmentErrorCode.LISTING_NOT_FOUND,
                "UPDATE recruitment_listings SET deleted_at = NOW() WHERE id = :listing"),
        ATTEND_PARTICIPANT_DELETED("出席: 参加者が削除された", Hook.ADMIN, RecruitmentErrorCode.LISTING_NOT_FOUND,
                "DELETE FROM recruitment_participants WHERE id = :participant"),
        ATTEND_LISTING_DELETED("出席: 親の募集が論理削除された", Hook.ADMIN, RecruitmentErrorCode.LISTING_NOT_FOUND,
                "UPDATE recruitment_listings SET deleted_at = NOW() WHERE id = :listing"),
        TEMPLATE_GET_DELETED("テンプレート詳細: アーカイブされた", Hook.MEMBER, RecruitmentErrorCode.TEMPLATE_NOT_FOUND,
                "UPDATE recruitment_templates SET deleted_at = NOW() WHERE id = :template"),
        TEMPLATE_PATCH_DELETED("テンプレート編集: アーカイブされた", Hook.ADMIN,
                RecruitmentErrorCode.TEMPLATE_NOT_FOUND,
                "UPDATE recruitment_templates SET deleted_at = NOW() WHERE id = :template"),
        TEMPLATE_ARCHIVE_DELETED("テンプレート論理削除: 先にアーカイブされた", Hook.ADMIN,
                RecruitmentErrorCode.TEMPLATE_NOT_FOUND,
                "UPDATE recruitment_templates SET deleted_at = NOW() WHERE id = :template");

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
            case UPDATE_LISTING_DELETED -> updateRequest(listingId);
            case PUBLISH_LISTING_DELETED -> post("/api/v1/recruitment-listings/{id}/publish", draftId);
            case CANCEL_LISTING_DELETED -> cancelRequest(listingId);
            case ARCHIVE_LISTING_DELETED -> post("/api/v1/recruitment-listings/{id}/archive", listingId);
            case DIST_GET_LISTING_DELETED -> get("/api/v1/recruitment-listings/{id}/distribution-targets", listingId);
            case DIST_PUT_LISTING_DELETED -> put("/api/v1/recruitment-listings/{id}/distribution-targets", listingId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(toJson(Map.of("targetTypes", List.of(RecruitmentDistributionTargetType.MEMBERS.name()))));
            case PARTICIPANTS_LISTING_DELETED -> participantsRequest(listingId);
            case ATTEND_PARTICIPANT_DELETED, ATTEND_LISTING_DELETED -> patch(
                    "/api/v1/recruitment-listings/{listingId}/participants/{participantId}/attend",
                    listingId, attendParticipantId);
            case TEMPLATE_GET_DELETED -> get("/api/v1/recruitment-templates/{id}", templateId);
            case TEMPLATE_PATCH_DELETED -> templatePatchRequest();
            case TEMPLATE_ARCHIVE_DELETED -> post("/api/v1/recruitment-templates/{id}/archive", templateId);
        };
    }

    @ParameterizedTest(name = "K1: {0}")
    @EnumSource(RaceCase.class)
    @DisplayName("K1: 認可の後・tx の前に対象や親が消えたら、対象の不在コードと同じ 404 になり DB は変わらない")
    void 認可後に対象や親が消えたら404でDB不変(RaceCase c) throws Exception {
        AtomicBoolean fired = new AtomicBoolean(false);
        AtomicReference<String> afterHook = new AtomicReference<>();
        Answer<Object> hook = inv -> {
            Object result = inv.callRealMethod();
            if (Boolean.TRUE.equals(result) && calledFromRecruitmentFacade() && fired.compareAndSet(false, true)) {
                runInNewTx(c.deleteSql);
                afterHook.set(snapshot());
            }
            return result;
        };
        if (c.hook == Hook.MEMBER) {
            Mockito.doAnswer(hook).when(accessControlService).isMember(any(), any(), any());
        } else {
            Mockito.doAnswer(hook).when(accessControlService).isAdminOrAbove(any(), any(), any());
        }

        setAuth(c.hook == Hook.MEMBER ? memberId : adminId);
        MvcResult result = mockMvc.perform(raceRequest(c)).andReturn();

        // フックが走らなければ 404 の根拠が崩れるので先に見る（是正前は Facade が無く、ここで落ちる）。
        assertThat(fired.get()).as("recruitment の Facade の中で許可判定が終わった直後にフックが走ること").isTrue();
        assertNotFound(result, c.expected);
        assertThat(snapshot()).as("フックで消した直後から、募集・参加者・履歴・配信対象・テンプレート・NO_SHOW 記録は 1 列も変わらない")
                .isEqualTo(afterHook.get());
    }

    /** 呼び出しが recruitment の {@code *Facade}（tx の外の認可層）の中からか。 */
    private static boolean calledFromRecruitmentFacade() {
        return Arrays.stream(Thread.currentThread().getStackTrace())
                .anyMatch(e -> e.getClassName().startsWith("com.mannschaft.app.recruitment.")
                        && e.getClassName().endsWith("Facade"));
    }

    // ═════════════════════════════════════════════════════════════════════
    // K6 / AC-19: 拒否経路で FOR UPDATE を発行しない
    // ═════════════════════════════════════════════════════════════════════

    private enum LockedEp {
        UPDATE, PUBLISH, CANCEL, ARCHIVE, APPLY, DISPUTE
    }

    private MockHttpServletRequestBuilder lockedRequest(LockedEp ep) {
        return switch (ep) {
            case UPDATE -> updateRequest(listingId);
            case PUBLISH -> post("/api/v1/recruitment-listings/{id}/publish", draftId);
            case CANCEL -> cancelRequest(listingId);
            case ARCHIVE -> post("/api/v1/recruitment-listings/{id}/archive", listingId);
            case APPLY -> post("/api/v1/recruitment-listings/{id}/applications", listingId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(toJson(Map.of("participantType", RecruitmentParticipantType.USER.name())));
            case DISPUTE -> post("/api/v1/recruitment/no-shows/{id}/dispute", noShowId)
                    .contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("reason", "W5 競合テスト")));
        };
    }

    @ParameterizedTest(name = "K6: {0}")
    @EnumSource(LockedEp.class)
    @DisplayName("K6: 部外者の要求は FOR UPDATE を 1 本も発行せず、不在と同一の 404 になる（SQL 記録）")
    void 部外者はFOR_UPDATEを発行しない(LockedEp ep) throws Exception {
        setAuth(outsiderId);
        SqlIntentCounter.reset();
        MvcResult result = mockMvc.perform(lockedRequest(ep)).andReturn();
        List<String> sqls = new ArrayList<>(SqlIntentCounter.capturedSqls());
        assertThat(sqls).as("SQL 記録が有効であること（StatementInspector の登録）").isNotEmpty();
        assertThat(sqls.stream().filter(RecruitmentListingFacadeRaceAndQueryIT::isForUpdate).toList())
                .as("拒否経路で行ロックを取らない").isEmpty();
        assertNotFound(result, ep == LockedEp.DISPUTE
                ? RecruitmentErrorCode.NO_SHOW_RECORD_NOT_FOUND : RecruitmentErrorCode.LISTING_NOT_FOUND);
    }

    // ═════════════════════════════════════════════════════════════════════
    // P2: 閲覧できない同スコープ在籍者の申込は、ファサードが是正前の 403 で返す（FOR UPDATE 0 本）
    // ═════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("K6/P2: 同スコープ一般メンバーが F00 の拒否する OPEN（CUSTOM_TEMPLATE）に申し込むと、是正前の403 VISIBILITY_001。FOR UPDATE は 0 本")
    void 同スコープ在籍者の可視性拒否はロックを取らず403() throws Exception {
        Long custom = tx.execute(s -> insertListing(RecruitmentListingStatus.OPEN,
                RecruitmentVisibility.CUSTOM_TEMPLATE));
        setAuth(memberId);
        SqlIntentCounter.reset();
        MvcResult result = mockMvc.perform(post("/api/v1/recruitment-listings/{id}/applications", custom)
                .contentType(MediaType.APPLICATION_JSON)
                .content(toJson(Map.of("participantType", RecruitmentParticipantType.USER.name())))).andReturn();
        List<String> sqls = new ArrayList<>(SqlIntentCounter.capturedSqls());
        assertThat(sqls).as("SQL 記録が有効であること（StatementInspector の登録）").isNotEmpty();
        assertThat(sqls.stream().filter(RecruitmentListingFacadeRaceAndQueryIT::isForUpdate).toList())
                .as("拒否経路で行ロックを取らない").isEmpty();
        String content = result.getResponse().getContentAsString();
        assertThat(result.getResponse().getStatus()).as(content).isEqualTo(403);
        assertThat((String) JsonPath.read(content, "$.error.code"))
                .isEqualTo(com.mannschaft.app.common.visibility.VisibilityErrorCode.VISIBILITY_001.getCode());
    }

    // ═════════════════════════════════════════════════════════════════════
    // P3 / AC-18: 下書きの GET・試算（作成者でない管理者）— 認可クエリは是正前と同数（二重判定しない）
    // ═════════════════════════════════════════════════════════════════════

    @ParameterizedTest(name = "P3: {0}")
    @org.junit.jupiter.params.provider.ValueSource(strings = {"detail", "estimate"})
    @DisplayName("AC-18/P3: 下書きの GET 詳細・試算（作成者でない管理者）— 認可クエリは是正前（isAdminOrAbove 単体）と同数で、管理者判定は 1 回だけ")
    void 下書き閲覧_作成者でない管理者の認可クエリ回数(String kind) throws Exception {
        Long otherAdmin = tx.execute(s -> {
            Long id = insertUser("w5r-admin2-" + suffix + "@example.com");
            MembershipTestHelper.insertMembership(em, id, ScopeType.TEAM, teamId, RoleKind.MEMBER);
            MembershipTestHelper.insertUserRole(em, id, "ADMIN", teamId, null);
            return id;
        });
        long baseline = adminCheckBaseline(otherAdmin);
        MockHttpServletRequestBuilder request = kind.equals("detail")
                ? get("/api/v1/recruitment-listings/{id}", draftId)
                : get("/api/v1/recruitment-listings/{id}/cancellation-fee-estimate", draftId);
        Measured m = measure(otherAdmin, request);
        m.print("draft " + kind);
        assertThat(m.status).isEqualTo(200);
        assertAuthzNotIncreased(m, baseline);
        Mockito.verify(accessControlService, Mockito.times(1)).isAdminOrAbove(any(), any(), any());
        Mockito.verify(accessControlService, never()).isMember(any(), any(), any());
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-12 / AC-18 / AC-19: 許可経路のクエリ回数（認可 / scope 解決 / tx 本体を分けて数える）
    // ═════════════════════════════════════════════════════════════════════

    private enum AdminWriteEp {
        UPDATE, PUBLISH, CANCEL, ARCHIVE
    }

    @ParameterizedTest(name = "AC-18: {0}")
    @EnumSource(AdminWriteEp.class)
    @DisplayName("AC-18/AC-19: 募集の書込（管理者）— 認可クエリは是正前と同数、認可の前は素の読み取り 1 本、FOR UPDATE は認可の後にだけ 1 本")
    void 募集書込_許可経路のクエリ回数(AdminWriteEp ep) throws Exception {
        long baseline = adminCheckBaseline();
        MockHttpServletRequestBuilder request = switch (ep) {
            case UPDATE -> updateRequest(listingId);
            case PUBLISH -> post("/api/v1/recruitment-listings/{id}/publish", draftId);
            case CANCEL -> cancelRequest(listingId);
            case ARCHIVE -> post("/api/v1/recruitment-listings/{id}/archive", listingId);
        };
        Measured m = measure(adminId, request);
        m.print(ep.name());
        assertThat(m.status).isBetween(200, 204);
        assertAuthzNotIncreased(m, baseline);
        Mockito.verify(accessControlService, never()).isMember(any(), any(), any());
        assertThat(m.count(m.beforeAuthz(), "recruitment_listings", true)).as("認可の前の FOR UPDATE").isZero();
        assertThat(m.count(m.beforeAuthz(), "recruitment_listings", false)).as("認可の前の素の読み取り（scope 解決）")
                .isEqualTo(1);
        assertThat(m.count(m.afterAuthz(), "recruitment_listings", true)).as("認可の後の FOR UPDATE").isEqualTo(1);
    }

    @Test
    @DisplayName("AC-18/C6: 参加者一覧（管理者）— 0・1・複数件で認可クエリは是正前と同数、参加者の SELECT 数も一定、募集の読み直しは認可の後に 1 本")
    void 参加者一覧_件数に依らずクエリ回数一定() throws Exception {
        Long one = tx.execute(s -> {
            Long id = insertListing(RecruitmentListingStatus.OPEN);
            insertParticipant(id, memberId, RecruitmentParticipantStatus.APPLIED);
            return id;
        });
        Long many = tx.execute(s -> {
            Long id = insertListing(RecruitmentListingStatus.OPEN);
            for (int i = 0; i < 3; i++) {
                insertParticipant(id, memberId, RecruitmentParticipantStatus.APPLIED);
            }
            return id;
        });
        long baseline = adminCheckBaseline();
        List<Long> participantSelects = new ArrayList<>();
        for (Long target : List.of(draftId, one, many)) {
            Measured m = measure(adminId, participantsRequest(target));
            m.print("participants listing=" + target);
            assertThat(m.status).isEqualTo(200);
            assertAuthzNotIncreased(m, baseline);
            Mockito.verify(accessControlService, never()).isMember(any(), any(), any());
            assertSelects(m, "recruitment_listings", 1, 1);
            participantSelects.add(m.count(m.sqls, "recruitment_participants", false));
        }
        assertThat(participantSelects).as("参加者の SELECT 数は件数に依らず一定（N+1 が無い）")
                .containsOnly(participantSelects.get(0));
    }

    @Test
    @DisplayName("AC-18: テンプレート編集（管理者）— 認可クエリは是正前と同数、テンプレートの読み直しは認可の後にちょうど 1 巡")
    void テンプレート編集_許可経路のクエリ回数() throws Exception {
        long baseline = adminCheckBaseline();
        Measured m = measure(adminId, templatePatchRequest());
        m.print("template patch");
        assertThat(m.status).isEqualTo(200);
        assertAuthzNotIncreased(m, baseline);
        Mockito.verify(accessControlService, never()).isMember(any(), any(), any());
        assertSelects(m, "recruitment_templates", 1, 1);
    }

    @Test
    @DisplayName("AC-18: テンプレート詳細（メンバー）— 認可クエリは是正前（isMember 単体）と同数、SYSTEM_ADMIN・管理者判定を足さない、読み直しは認可の後に 1 巡")
    void テンプレート詳細_許可経路のクエリ回数() throws Exception {
        accessControlService.isMember(memberId, teamId, "TEAM");
        SqlIntentCounter.reset();
        accessControlService.isMember(memberId, teamId, "TEAM");
        long baseline = SqlIntentCounter.totalCount();
        assertThat(baseline).as("SQL 記録が有効であること").isPositive();
        Mockito.clearInvocations(accessControlService);

        Measured m = measure(memberId, get("/api/v1/recruitment-templates/{id}", templateId));
        m.print("template get");
        assertThat(m.status).isEqualTo(200);
        assertThat(m.authzSql).as("認可のクエリ数は是正前（isMember 単体）と同じ").isEqualTo(baseline);
        Mockito.verify(accessControlService, never()).isSystemAdmin(any());
        Mockito.verify(accessControlService, never()).isAdminOrAbove(any(), any(), any());
        assertSelects(m, "recruitment_templates", 1, 1);
    }

    /** 是正前の管理者判定（{@code isAdminOrAbove} 単体）のクエリ数。初回コストを外すため 1 度流してから測る。 */
    private long adminCheckBaseline() {
        return adminCheckBaseline(adminId);
    }

    private long adminCheckBaseline(Long who) {
        accessControlService.isAdminOrAbove(who, teamId, "TEAM");
        SqlIntentCounter.reset();
        accessControlService.isAdminOrAbove(who, teamId, "TEAM");
        long baseline = SqlIntentCounter.totalCount();
        assertThat(baseline).as("SQL 記録が有効であること").isPositive();
        Mockito.clearInvocations(accessControlService);
        return baseline;
    }

    private void assertAuthzNotIncreased(Measured m, long baseline) {
        assertThat(m.authzSql).as("認可のクエリ数は是正前（isAdminOrAbove 単体）と同じ").isEqualTo(baseline);
        // SYSTEM_ADMIN は是正前どおり通さない（裁可 2026-09-30）ので、許可経路で SYSTEM_ADMIN 判定を足さない。
        Mockito.verify(accessControlService, never()).isSystemAdmin(any());
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
            System.out.printf("[W5 AC-18] %s status=%d authzSql=%d firstAuthzStart=%d lastAuthzEnd=%d total=%d%n%s%n",
                    label, status, authzSql, firstAuthzStart, lastAuthzEnd, sqls.size(),
                    sqls.stream().map(s -> "  " + s).collect(Collectors.joining("\n")));
        }
    }

    /**
     * 認可の呼び出し（AccessControlService の判定）を包み、その中で発行された SQL を数えながら
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
        Mockito.clearInvocations(accessControlService);

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

    private MockHttpServletRequestBuilder updateRequest(Long id) {
        return patch("/api/v1/recruitment-listings/{id}", id)
                .contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("title", "W5 競合テスト")));
    }

    private MockHttpServletRequestBuilder cancelRequest(Long id) {
        return post("/api/v1/recruitment-listings/{id}/cancel", id)
                .contentType(MediaType.APPLICATION_JSON).content(toJson(Map.of("reason", "W5 競合テスト")));
    }

    private MockHttpServletRequestBuilder participantsRequest(Long id) {
        return get("/api/v1/recruitment-listings/{id}/participants", id);
    }

    private MockHttpServletRequestBuilder templatePatchRequest() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("templateName", "W5 編集後");
        return patch("/api/v1/recruitment-templates/{id}", templateId)
                .contentType(MediaType.APPLICATION_JSON).content(toJson(body));
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
     * フックは認可（AccessControlService の readOnly tx）の呼び出しの中で走るため、既存 tx に合流させず
     * {@code REQUIRES_NEW} の別 tx で書いてコミットする。
     */
    private void runInNewTx(String sql) {
        TransactionTemplate requiresNew = new TransactionTemplate(transactionManager);
        requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        requiresNew.executeWithoutResult(s -> {
            var q = em.createNativeQuery(sql);
            Map<String, Object> params = Map.of("listing", listingId, "draft", draftId,
                    "participant", attendParticipantId, "template", templateId);
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
        String listings = "(SELECT id FROM recruitment_listings WHERE category_id = " + categoryId + ")";
        return requiresNew.execute(s -> Stream.of(
                        "SELECT * FROM recruitment_listings WHERE category_id = " + categoryId + " ORDER BY id",
                        "SELECT * FROM recruitment_participants WHERE listing_id IN " + listings + " ORDER BY id",
                        "SELECT * FROM recruitment_participant_history WHERE listing_id IN " + listings + " ORDER BY id",
                        "SELECT * FROM recruitment_distribution_targets WHERE listing_id IN " + listings
                                + " ORDER BY id",
                        "SELECT * FROM recruitment_no_show_records WHERE listing_id IN " + listings + " ORDER BY id",
                        "SELECT * FROM recruitment_templates WHERE category_id = " + categoryId + " ORDER BY id")
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

    private Long insertListing(RecruitmentListingStatus status) {
        return insertListing(status, RecruitmentVisibility.SCOPE_ONLY);
    }

    private Long insertListing(RecruitmentListingStatus status, RecruitmentVisibility visibility) {
        LocalDateTime start = LocalDateTime.now().plusDays(30);
        return listingRepository.save(RecruitmentListingEntity.builder()
                .scopeType(RecruitmentScopeType.TEAM)
                .scopeId(teamId)
                .categoryId(categoryId)
                .title("W5RACE 募集")
                .participationType(RecruitmentParticipationType.INDIVIDUAL)
                .startAt(start)
                .endAt(start.plusHours(2))
                .applicationDeadline(start.minusDays(1))
                .autoCancelAt(start.minusDays(2))
                .capacity(10)
                .minCapacity(1)
                .status(status)
                .visibility(visibility)
                .location("W5RACE 会場")
                .createdBy(adminId)
                .build()).getId();
    }

    private Long insertParticipant(Long listing, Long userId, RecruitmentParticipantStatus status) {
        return participantRepository.save(RecruitmentParticipantEntity.builder()
                .listingId(listing)
                .participantType(RecruitmentParticipantType.USER)
                .userId(userId)
                .appliedBy(userId)
                .status(status)
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
                                + "VALUES (:email, 'W5RACE', 'テスト', 'W5RACE テスト', 'ACTIVE', "
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
                                + "CONCAT('w5r-', LEFT(REPLACE(UUID(),'-',''),8)), NOW(), NOW())")
                .setParameter("name", name)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM teams WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult()).longValue();
    }
}
