package com.mannschaft.app.recruitment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.visibility.VisibilityErrorCode;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * recruitment の募集（非公開物）・テンプレート・no-show 異議申立の認可契約テスト
 * （CMP-260923-0954 W5・存在オラクル是正。試練＝実装前の red）。
 *
 * <p>越境（スコープに所属しない利用者）が実在 ID を叩いた応答と、同じ利用者が不在 ID を叩いた応答を
 * status・error.code・error.message まで一致させる。同一スコープ内の権限不足は従来どおり 403、
 * 公開中（PUBLIC かつ OPEN）の募集への越境書込は 403 のまま・GET は 200 のまま（AC-4）。
 * SYSTEM_ADMIN は是正前の応答をコードから読んで固定する（マスター裁可 2026-09-30）。
 * 限定公開の募集の GET（F00 の 403/404 返し分け）は範囲外（殿の判断 2026-09-30）。</p>
 *
 * <h2>EP 別許可主体表（左: 是正前 / 右: 是正後。募集は teamA・SCOPE_ONLY を基本とする）</h2>
 * <pre>
 * ■ 募集の書込系 8 EP（PATCH /{id}・publish・cancel・archive・distribution-targets GET/PUT・
 *   participants 一覧・participants/{pid}/attend）
 *   部外者・他チームADMIN × 非公開物（SCOPE_ONLY の OPEN / DRAFT / CANCELLED） | 403 C002 → 404 R001（不在と完全一致）
 *   部外者 × ORG 募集                         | 403 C002 → 404 R001
 *   ORG の一般メンバー                         | 403 C002 → 403 C002
 *   部外者 × PERSONAL 募集（OPEN・DRAFT）       | 404 MARKET_404 / 403 C002（publish・配信対象）→ 404 R001
 *   SYSTEM_ADMIN × PERSONAL 募集               | 404 MARKET_404 → 404 R001 / publish・配信対象は 403 C002 のまま
 *   同スコープ一般メンバー                     | 403 C002 → 403 C002
 *   スコープ ADMIN                             | 2xx → 2xx
 *   user_roles のみの ADMIN（在籍なし）        | 2xx → 2xx（AC-3）
 *   非メンバー／メンバー SYSTEM_ADMIN          | 403 C002 → 403 C002（新規許可も 404 化もしない）
 *   部外者・他チームADMIN × 公開物（PUBLIC・OPEN）| 403 C002 → 403 C002（AC-4）
 *   対象不在・論理削除済み・0・負数・MAX       | 404 R001 → 404 R001 / 非数値 400
 *   CANCELLED への cancel（スコープ ADMIN）     | 409 R102 → 409 R102（状態判定は認可の後）
 *
 * ■ GET /recruitment-listings/{id}（DRAFT・PERSONAL のみ。限定公開の非 DRAFT は範囲外）
 *   TEAM DRAFT × 部外者・他チームADMIN         | 403 R020 → 404 R001
 *   TEAM DRAFT × 同スコープ一般メンバー        | 403 R020 → 403 R020（回帰型 b: C002 に寄せない）
 *   TEAM DRAFT × 作成者・ADMIN・user_roles のみ ADMIN | 200 → 200
 *   TEAM DRAFT × SYSTEM_ADMIN（非メンバー／メンバー） | 403 R020 → 403 R020
 *   PERSONAL DRAFT × 本人 / 部外者 / SYSTEM_ADMIN | 200 / 403 R020 / 403 R020 → 200 / 404 R001 / 403 R020
 *   PERSONAL 非 DRAFT × 誰でも（本人・部外者・SYSTEM_ADMIN）| 404 MARKET_404 → 404 R001
 *   TEAM PUBLIC OPEN × 部外者                  | 200 → 200（AC-4）
 *   cancellation-fee-estimate（GET 詳細を再利用）× TEAM DRAFT の部外者 | 403 R020 → 404 R001
 *
 * ■ GET / PATCH / POST archive /recruitment-templates/{id}（テンプレート = teamA）
 *   部外者・他チームADMIN                      | 403 C002 → 404 R313
 *   ORG テンプレート × 部外者                  | 403 C002 → 404 R313
 *   同スコープ一般メンバー                     | GET 200 / PATCH・archive 403 C002 → 同じ
 *   user_roles のみの ADMIN                    | GET 403 C002 / PATCH 200 / archive 204 → 同じ（AC-3 従来どおり）
 *   非メンバー SYSTEM_ADMIN                    | 403 C002（3 EP）→ 同じ
 *   メンバー SYSTEM_ADMIN                      | GET 200 / PATCH・archive 403 C002 → 同じ
 *   対象不在・アーカイブ済み・0・負数・MAX     | 404 R313 → 404 R313 / 非数値 400
 *
 * ■ POST /{teams|organizations}/{id}/recruitment-listings/from-template（C3）
 *   パス scope の ADMIN × 他スコープの実在テンプレート | 404 R314 → 404 R313（不在と完全一致）
 *   パス scope の非管理者・他チームADMIN・SYSTEM_ADMIN | 403 C002 → 403 C002（templateId 非依存）
 *   templateId null                            | 400 → 400
 *
 * ■ POST /recruitment/no-shows/{id}/dispute（記録の本人のみ。Gate を使わない）
 *   本人以外（同チームADMIN・user_roles のみ ADMIN・他チームADMIN・部外者・SYSTEM_ADMIN 2 種）
 *                                              | 404 R003 → 404 R309（403 にしない・回帰型 a/c）
 *   本人以外 × 申立済みの記録                  | 404 R003 → 404 R309（409 を返さない）
 *   本人                                       | 200 / 申立済み 409 R311 → 同じ
 *   不在・0・負数・MAX                         | 404 R309 → 404 R309 / 非数値 400
 *
 * ■ POST /recruitment-listings/{id}/applications（可視性判定を状態判定より前へ）
 *   部外者・他チームADMIN × SCOPE_ONLY の OPEN / DRAFT / 締切超過 / CANCELLED
 *                                              | 403 V001 / 409 R103 / 400 R101 / 409 R100 → 404 R001（4 つとも不在と完全一致）
 *   同スコープメンバー × OPEN / 締切超過       | 201 / R101 → 同じ
 *   部外者 × PUBLIC OPEN                       | 201 → 201
 *   非メンバー SYSTEM_ADMIN × SCOPE_ONLY OPEN  | 201 → 201（F00 の SystemAdmin 高速パス。是正前から許可）
 * </pre>
 *
 * <p>競合（認可の後・tx の前の削除）・FOR UPDATE の順序・クエリ回数は
 * {@code RecruitmentListingFacadeRaceAndQueryIT}、Facade 構成は {@code RecruitmentListingTxFacadeArchTest} が固定する。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("recruitment 募集・テンプレート・no-show 異議 認可契約テスト（W5 存在オラクル）")
class RecruitmentListingTemplateScopeContractIT extends AbstractMySqlIntegrationTest {

    private static final long MISSING_ID = 987_654_321L;
    private static final List<String> BOUNDARY_IDS = List.of("0", "-1", String.valueOf(Long.MAX_VALUE));

    private static final String C002 = CommonErrorCode.COMMON_002.getCode();
    private static final String V001 = VisibilityErrorCode.VISIBILITY_001.getCode();
    private static final String R001 = RecruitmentErrorCode.LISTING_NOT_FOUND.getCode();
    private static final String R020 = RecruitmentErrorCode.DRAFT_VIEW_DENIED.getCode();
    private static final String R101 = RecruitmentErrorCode.DEADLINE_EXCEEDED.getCode();
    private static final String R103 = RecruitmentErrorCode.DRAFT_NOT_APPLICABLE.getCode();
    private static final String R102 = RecruitmentErrorCode.ALREADY_CANCELLED.getCode();
    private static final String R309 = RecruitmentErrorCode.NO_SHOW_RECORD_NOT_FOUND.getCode();
    private static final String R311 = RecruitmentErrorCode.ALREADY_DISPUTED.getCode();
    private static final String R313 = RecruitmentErrorCode.TEMPLATE_NOT_FOUND.getCode();

    /** 募集の書込系 8 EP。 */
    enum ListingEp {
        UPDATE, PUBLISH, CANCEL, ARCHIVE, DIST_GET, DIST_PUT, PARTICIPANTS, ATTEND
    }

    /** テンプレートの 3 EP。 */
    enum TemplateEp {
        GET, PATCH, ARCHIVE
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
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

    /** 外部境界（Stripe）のみ差し替える。W4 の契約 IT と同じ構成にしてコンテキストを共有する。 */
    @MockitoBean
    private com.mannschaft.app.payment.stripe.StripePaymentProvider stripePaymentProvider;

    @PersistenceContext
    private EntityManager em;

    // ---- スコープ ----
    private Long teamAId;
    private Long teamBId;
    private Long orgId;
    private Long categoryId;

    // ---- 主体 ----
    private Long adminAId;
    private Long memberAId;
    private Long urAdminAId;
    private Long adminBId;
    private Long outsiderId;
    private Long systemAdminId;
    private Long memberSystemAdminId;
    private Long personalOwnerId;
    private Long orgAdminId;
    private Long orgMemberId;

    // ---- 募集 ----
    private Long listingAId;
    private Long listingDraftAId;
    private Long listingCancelledAId;
    private Long listingPublicAId;
    private Long listingOrgId;
    private Long listingPersonalId;
    private Long listingPersonalDraftId;
    private Long listingDeletedAId;
    private Long listingDeadlinePassedAId;
    /** CUSTOM_TEMPLATE の OPEN（同スコープ一般メンバーも F00 が拒否する。是正前は 403 VISIBILITY_001）。 */
    private Long listingCustomAId;
    /** 募集 ID → その募集の CONFIRMED 参加者 ID（attend 用）。 */
    private final Map<Long, Long> attendParticipantByListing = new HashMap<>();

    // ---- テンプレート ----
    private Long templateAId;
    private Long templateBId;
    private Long templateOrgId;
    private Long templateArchivedAId;

    // ---- NO_SHOW 記録 ----
    private Long noShowAId;
    private Long noShowDisputedAId;

    @BeforeEach
    void setUp() {
        attendParticipantByListing.clear();
        teamAId = insertTeam("W5LIST チームA");
        teamBId = insertTeam("W5LIST チームB");
        orgId = insertOrganization("W5LIST 組織");

        adminAId = insertUser("w5l-admin-a@example.com");
        memberAId = insertUser("w5l-member-a@example.com");
        urAdminAId = insertUser("w5l-ur-admin-a@example.com");
        adminBId = insertUser("w5l-admin-b@example.com");
        outsiderId = insertUser("w5l-outsider@example.com");
        systemAdminId = insertUser("w5l-sysadmin@example.com");
        memberSystemAdminId = insertUser("w5l-member-sysadmin@example.com");
        personalOwnerId = insertUser("w5l-personal-owner@example.com");
        orgAdminId = insertUser("w5l-org-admin@example.com");
        orgMemberId = insertUser("w5l-org-member@example.com");

        MembershipTestHelper.insertMembership(em, adminAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminAId, "ADMIN", teamAId, null);
        MembershipTestHelper.insertMembership(em, memberAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, urAdminAId, "ADMIN", teamAId, null);
        MembershipTestHelper.insertMembership(em, adminBId, ScopeType.TEAM, teamBId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminBId, "ADMIN", teamBId, null);
        MembershipTestHelper.insertUserRole(em, systemAdminId, "SYSTEM_ADMIN", null, null);
        MembershipTestHelper.insertMembership(em, memberSystemAdminId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, memberSystemAdminId, "SYSTEM_ADMIN", null, null);
        MembershipTestHelper.insertMembership(em, orgAdminId, ScopeType.ORGANIZATION, orgId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, orgAdminId, "ADMIN", null, orgId);
        MembershipTestHelper.insertMembership(em, orgMemberId, ScopeType.ORGANIZATION, orgId, RoleKind.MEMBER);

        categoryId = categoryRepository.save(RecruitmentCategoryEntity.builder()
                .code("W5LIST_TEST")
                .nameI18nKey("recruitment.category.w5listTest")
                .defaultParticipationType(RecruitmentParticipationType.INDIVIDUAL)
                .displayOrder(0)
                .isActive(true)
                .build()).getId();

        LocalDateTime start = LocalDateTime.now().plusDays(30);
        listingAId = insertListing(RecruitmentScopeType.TEAM, teamAId, RecruitmentVisibility.SCOPE_ONLY,
                RecruitmentListingStatus.OPEN, adminAId, start.minusDays(1));
        listingDraftAId = insertListing(RecruitmentScopeType.TEAM, teamAId, RecruitmentVisibility.SCOPE_ONLY,
                RecruitmentListingStatus.DRAFT, adminAId, start.minusDays(1));
        listingCancelledAId = insertListing(RecruitmentScopeType.TEAM, teamAId, RecruitmentVisibility.SCOPE_ONLY,
                RecruitmentListingStatus.CANCELLED, adminAId, start.minusDays(1));
        listingPublicAId = insertListing(RecruitmentScopeType.TEAM, teamAId, RecruitmentVisibility.PUBLIC,
                RecruitmentListingStatus.OPEN, adminAId, start.minusDays(1));
        listingOrgId = insertListing(RecruitmentScopeType.ORGANIZATION, orgId, RecruitmentVisibility.SCOPE_ONLY,
                RecruitmentListingStatus.OPEN, orgAdminId, start.minusDays(1));
        listingPersonalId = insertListing(RecruitmentScopeType.PERSONAL, personalOwnerId,
                RecruitmentVisibility.SCOPE_ONLY, RecruitmentListingStatus.OPEN, personalOwnerId, start.minusDays(1));
        listingPersonalDraftId = insertListing(RecruitmentScopeType.PERSONAL, personalOwnerId,
                RecruitmentVisibility.SCOPE_ONLY, RecruitmentListingStatus.DRAFT, personalOwnerId, start.minusDays(1));
        listingDeletedAId = insertListing(RecruitmentScopeType.TEAM, teamAId, RecruitmentVisibility.SCOPE_ONLY,
                RecruitmentListingStatus.OPEN, adminAId, start.minusDays(1));
        listingDeadlinePassedAId = insertListing(RecruitmentScopeType.TEAM, teamAId,
                RecruitmentVisibility.SCOPE_ONLY, RecruitmentListingStatus.OPEN, adminAId,
                LocalDateTime.now().minusDays(1));

        listingCustomAId = insertListing(RecruitmentScopeType.TEAM, teamAId, RecruitmentVisibility.CUSTOM_TEMPLATE,
                RecruitmentListingStatus.OPEN, adminAId, start.minusDays(1));

        for (Long id : List.of(listingAId, listingDraftAId, listingCancelledAId, listingPublicAId, listingOrgId,
                listingPersonalId, listingPersonalDraftId, listingDeletedAId)) {
            attendParticipantByListing.put(id,
                    insertParticipant(id, memberAId, RecruitmentParticipantStatus.CONFIRMED));
            insertDistributionTarget(id, RecruitmentDistributionTargetType.MEMBERS);
        }

        templateAId = insertTemplate(RecruitmentScopeType.TEAM, teamAId);
        templateBId = insertTemplate(RecruitmentScopeType.TEAM, teamBId);
        templateOrgId = insertTemplate(RecruitmentScopeType.ORGANIZATION, orgId);
        templateArchivedAId = insertTemplate(RecruitmentScopeType.TEAM, teamAId);

        // NO_SHOW 記録は実在する募集・参加者に結ぶ（期限算出が募集を JOIN するため）
        Long noShowParticipant = insertParticipant(listingAId, memberAId, RecruitmentParticipantStatus.NO_SHOW);
        noShowAId = insertNoShow(listingAId, noShowParticipant, memberAId);
        noShowDisputedAId = insertNoShow(listingAId, noShowParticipant, memberAId);

        em.flush();
        em.createNativeQuery("UPDATE recruitment_listings SET deleted_at = NOW() WHERE id = :id")
                .setParameter("id", listingDeletedAId).executeUpdate();
        em.createNativeQuery("UPDATE recruitment_templates SET deleted_at = NOW() WHERE id = :id")
                .setParameter("id", templateArchivedAId).executeUpdate();
        em.createNativeQuery("UPDATE recruitment_no_show_records SET disputed = 1, dispute_reason = 'W5 申立済み' "
                        + "WHERE id = :id")
                .setParameter("id", noShowDisputedAId).executeUpdate();
        em.flush();
        em.clear();
    }

    // ═════════════════════════════════════════════════════════════════════
    // 1. 募集の書込系 8 EP
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("1. 募集の書込系（PATCH・publish・cancel・archive・配信対象 GET/PUT・参加者一覧・出席）")
    class ListingWrites {

        @ParameterizedTest(name = "{0}")
        @EnumSource(ListingEp.class)
        @DisplayName("AC-1: 部外者・他チームADMINは、非公開物（SCOPE_ONLY の OPEN/DRAFT/CANCELLED）の実在IDと不在IDで応答が一致（404 R001）")
        void 越境は不在と同一応答(ListingEp ep) throws Exception {
            for (Long actor : List.of(outsiderId, adminBId)) {
                setAuth(actor);
                for (Long listing : List.of(listingAId, listingDraftAId, listingCancelledAId)) {
                    assertSameAsMissing(listingEp(ep, listing), listingEp(ep, MISSING_ID), 404, R001);
                }
            }
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(ListingEp.class)
        @DisplayName("AC-11: 越境 404 のとき募集・参加者・履歴・配信対象は全列そのまま")
        void 越境でDB不変(ListingEp ep) throws Exception {
            String before = listingFootprint(listingAId);
            setAuth(outsiderId);
            mockMvc.perform(listingEp(ep, listingAId)).andReturn();
            assertThat(listingFootprint(listingAId)).isEqualTo(before);
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(ListingEp.class)
        @DisplayName("ORG 募集: 部外者は不在と同一の404、組織の一般メンバーは403 C002")
        void ORG募集(ListingEp ep) throws Exception {
            setAuth(outsiderId);
            assertSameAsMissing(listingEp(ep, listingOrgId), listingEp(ep, MISSING_ID), 404, R001);
            setAuth(orgMemberId);
            expectError(listingEp(ep, listingOrgId), 403, C002);
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(ListingEp.class)
        @DisplayName("PERSONAL 募集（OPEN・DRAFT）: 部外者は不在と同一の404 R001（MARKET_404・403 と割れない）")
        void PERSONAL募集は部外者に不在と同一(ListingEp ep) throws Exception {
            setAuth(outsiderId);
            for (Long listing : List.of(listingPersonalId, listingPersonalDraftId)) {
                assertSameAsMissing(listingEp(ep, listing), listingEp(ep, MISSING_ID), 404, R001);
            }
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(ListingEp.class)
        @DisplayName("PERSONAL 募集 × SYSTEM_ADMIN: 是正前に404だったEPはコードだけR001へ、403だったEP（publish・配信対象）は403 C002のまま")
        void PERSONAL募集のSYSTEM_ADMIN(ListingEp ep) throws Exception {
            setAuth(systemAdminId);
            boolean forbiddenBefore = ep == ListingEp.PUBLISH || ep == ListingEp.DIST_GET || ep == ListingEp.DIST_PUT;
            if (forbiddenBefore) {
                expectError(listingEp(ep, listingPersonalId), 403, C002);
            } else {
                assertSameAsMissing(listingEp(ep, listingPersonalId), listingEp(ep, MISSING_ID), 404, R001);
            }
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(value = ListingEp.class, names = {"UPDATE", "CANCEL", "ARCHIVE"})
        @DisplayName("殿の判断(b): PERSONAL 募集の本人が汎用の PATCH・cancel・archive を叩くと、是正前の MARKET_404 ではなく不在と同一の404 R001")
        void PERSONAL募集の本人は汎用EPで不在と同一(ListingEp ep) throws Exception {
            setAuth(personalOwnerId);
            for (Long listing : List.of(listingPersonalId, listingPersonalDraftId)) {
                assertSameAsMissing(listingEp(ep, listing), listingEp(ep, MISSING_ID), 404, R001);
            }
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(ListingEp.class)
        @DisplayName("AC-2: 同スコープ一般メンバーは403 C002のまま（OPEN・DRAFT）")
        void 同スコープ一般メンバーは403(ListingEp ep) throws Exception {
            setAuth(memberAId);
            expectError(listingEp(ep, listingAId), 403, C002);
            expectError(listingEp(ep, listingDraftAId), 403, C002);
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(ListingEp.class)
        @DisplayName("SYSTEM_ADMIN（非メンバー／メンバー）は是正前どおり403 C002（新規許可も404化もしない）")
        void SYSTEM_ADMINは従来どおり403(ListingEp ep) throws Exception {
            for (Long actor : List.of(systemAdminId, memberSystemAdminId)) {
                setAuth(actor);
                expectError(listingEp(ep, listingAId), 403, C002);
                expectError(listingEp(ep, listingDraftAId), 403, C002);
            }
            setAuth(systemAdminId);
            expectError(listingEp(ep, MISSING_ID), 404, R001);
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(ListingEp.class)
        @DisplayName("AC-4: 公開物（PUBLIC・OPEN）への越境書込は403 C002のまま（404に倒さない）")
        void 公開物への越境は403のまま(ListingEp ep) throws Exception {
            for (Long actor : List.of(outsiderId, adminBId)) {
                setAuth(actor);
                expectError(listingEp(ep, listingPublicAId), 403, C002);
            }
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(ListingEp.class)
        @DisplayName("論理削除済みの募集は、管理者にも部外者にも不在と同一の404")
        void 論理削除済みは不在と同一(ListingEp ep) throws Exception {
            for (Long actor : List.of(adminAId, outsiderId)) {
                setAuth(actor);
                assertSameAsMissing(listingEp(ep, listingDeletedAId), listingEp(ep, MISSING_ID), 404, R001);
            }
        }

        @Test
        @DisplayName("許可主体: スコープADMINは8EPすべて2xx（非回帰）")
        void スコープADMINは2xx() throws Exception {
            setAuth(adminAId);
            expectStatus(listingEp(ListingEp.DIST_GET, listingAId), 200);
            expectStatus(listingEp(ListingEp.DIST_PUT, listingAId), 200);
            expectStatus(listingEp(ListingEp.PARTICIPANTS, listingAId), 200);
            expectStatus(listingEp(ListingEp.ATTEND, listingAId), 200);
            expectStatus(listingEp(ListingEp.UPDATE, listingAId), 200);
            expectStatus(listingEp(ListingEp.PUBLISH, listingDraftAId), 200);
            expectStatus(listingEp(ListingEp.CANCEL, listingAId), 200);
            expectStatus(listingEp(ListingEp.ARCHIVE, listingDraftAId), 204);
        }

        @Test
        @DisplayName("AC-3: user_roles のみの ADMIN（在籍なし）は従来どおり許可（404 に化けない）")
        void userRolesOnly管理者は許可() throws Exception {
            setAuth(urAdminAId);
            expectStatus(listingEp(ListingEp.DIST_GET, listingAId), 200);
            expectStatus(listingEp(ListingEp.PARTICIPANTS, listingAId), 200);
            expectStatus(listingEp(ListingEp.UPDATE, listingAId), 200);
            expectStatus(listingEp(ListingEp.ARCHIVE, listingDraftAId), 204);
        }

        @Test
        @DisplayName("AC-7: CANCELLED への cancel はスコープADMINには409 R102、部外者には不在と同一の404")
        void 状態判定は認可の後() throws Exception {
            setAuth(adminAId);
            expectError(listingEp(ListingEp.CANCEL, listingCancelledAId), 409, R102);
            setAuth(outsiderId);
            assertSameAsMissing(listingEp(ListingEp.CANCEL, listingCancelledAId),
                    listingEp(ListingEp.CANCEL, MISSING_ID), 404, R001);
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(ListingEp.class)
        @DisplayName("AC-10: ID 0・負数・Long.MAXは不在と同じ404、非数値は400")
        void ID境界(ListingEp ep) throws Exception {
            for (Long actor : List.of(adminAId, outsiderId)) {
                setAuth(actor);
                for (String id : BOUNDARY_IDS) {
                    expectError(listingEpRaw(ep, id, attendParticipantByListing.get(listingAId)), 404, R001);
                }
                expectStatus(listingEpRaw(ep, "abc", attendParticipantByListing.get(listingAId)), 400);
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 2. GET /recruitment-listings/{id}（DRAFT・PERSONAL）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("2. GET /recruitment-listings/{id}（DRAFT・PERSONAL。限定公開の非 DRAFT は範囲外）")
    class ListingGet {

        @Test
        @DisplayName("AC-1: TEAM DRAFT は部外者・他チームADMINに不在と同一の404 R001")
        void TEAM_DRAFTの越境は不在と同一() throws Exception {
            for (Long actor : List.of(outsiderId, adminBId)) {
                setAuth(actor);
                assertSameAsMissing(getListing(listingDraftAId), getListing(MISSING_ID), 404, R001);
            }
        }

        @Test
        @DisplayName("AC-2: TEAM DRAFT は同スコープ一般メンバーに403 R020のまま（C002 に寄せない）")
        void TEAM_DRAFTの同スコープ一般メンバーは403_R020() throws Exception {
            setAuth(memberAId);
            expectError(getListing(listingDraftAId), 403, R020);
        }

        @Test
        @DisplayName("TEAM DRAFT は作成者（ADMIN）・user_roles のみの ADMIN に200（非回帰）")
        void TEAM_DRAFTの許可主体は200() throws Exception {
            for (Long actor : List.of(adminAId, urAdminAId)) {
                setAuth(actor);
                expectStatus(getListing(listingDraftAId), 200);
            }
        }

        @Test
        @DisplayName("TEAM DRAFT は SYSTEM_ADMIN（非メンバー／メンバー）に是正前どおり403 R020")
        void TEAM_DRAFTのSYSTEM_ADMINは403_R020() throws Exception {
            for (Long actor : List.of(systemAdminId, memberSystemAdminId)) {
                setAuth(actor);
                expectError(getListing(listingDraftAId), 403, R020);
            }
        }

        @Test
        @DisplayName("PERSONAL DRAFT: 本人は200、部外者は不在と同一の404 R001、SYSTEM_ADMIN は是正前どおり403 R020")
        void PERSONAL_DRAFT() throws Exception {
            setAuth(personalOwnerId);
            expectStatus(getListing(listingPersonalDraftId), 200);
            setAuth(outsiderId);
            assertSameAsMissing(getListing(listingPersonalDraftId), getListing(MISSING_ID), 404, R001);
            setAuth(systemAdminId);
            expectError(getListing(listingPersonalDraftId), 403, R020);
        }

        @Test
        @DisplayName("PERSONAL 非 DRAFT: 本人・部外者・SYSTEM_ADMIN のいずれにも不在と同一の404 R001（MARKET_404 と割れない）")
        void PERSONAL非DRAFTは誰にも不在と同一() throws Exception {
            for (Long actor : List.of(personalOwnerId, outsiderId, systemAdminId)) {
                setAuth(actor);
                assertSameAsMissing(getListing(listingPersonalId), getListing(MISSING_ID), 404, R001);
            }
        }

        @Test
        @DisplayName("AC-4: 公開物（TEAM PUBLIC OPEN）の GET は部外者に200のまま")
        void 公開物のGETは200() throws Exception {
            setAuth(outsiderId);
            expectStatus(getListing(listingPublicAId), 200);
        }

        @Test
        @DisplayName("cancellation-fee-estimate は GET 詳細に追随: TEAM DRAFT の部外者には不在と同一の404 R001")
        void 試算はGET詳細に追随() throws Exception {
            setAuth(outsiderId);
            assertSameAsMissing(get("/api/v1/recruitment-listings/{id}/cancellation-fee-estimate", listingDraftAId),
                    get("/api/v1/recruitment-listings/{id}/cancellation-fee-estimate", MISSING_ID), 404, R001);
        }

        @Test
        @DisplayName("AC-10: ID 0・負数・Long.MAXは不在と同じ404 R001、非数値は400")
        void ID境界() throws Exception {
            setAuth(outsiderId);
            for (String id : BOUNDARY_IDS) {
                expectError(get("/api/v1/recruitment-listings/" + id), 404, R001);
            }
            expectStatus(get("/api/v1/recruitment-listings/abc"), 400);
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 3. テンプレート GET / PATCH / archive
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("3. GET / PATCH / POST archive /recruitment-templates/{id}")
    class Templates {

        @ParameterizedTest(name = "{0}")
        @EnumSource(TemplateEp.class)
        @DisplayName("AC-1: 部外者・他チームADMINは、実在IDと不在IDで応答が一致（404 R313）")
        void 越境は不在と同一応答(TemplateEp ep) throws Exception {
            for (Long actor : List.of(outsiderId, adminBId)) {
                setAuth(actor);
                assertSameAsMissing(templateEp(ep, templateAId), templateEp(ep, MISSING_ID), 404, R313);
            }
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(TemplateEp.class)
        @DisplayName("ORG テンプレート: 部外者は不在と同一の404 R313")
        void ORGテンプレートの越境は不在と同一(TemplateEp ep) throws Exception {
            setAuth(outsiderId);
            assertSameAsMissing(templateEp(ep, templateOrgId), templateEp(ep, MISSING_ID), 404, R313);
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(value = TemplateEp.class, names = {"PATCH", "ARCHIVE"})
        @DisplayName("AC-11: 越境 404 のときテンプレートは全列そのまま")
        void 越境でDB不変(TemplateEp ep) throws Exception {
            String before = row("recruitment_templates", templateAId);
            setAuth(outsiderId);
            mockMvc.perform(templateEp(ep, templateAId)).andReturn();
            assertThat(row("recruitment_templates", templateAId)).isEqualTo(before);
        }

        @Test
        @DisplayName("AC-2: 同スコープ一般メンバーは GET 200、PATCH・archive は403 C002のまま")
        void 同スコープ一般メンバー() throws Exception {
            setAuth(memberAId);
            expectStatus(templateEp(TemplateEp.GET, templateAId), 200);
            expectError(templateEp(TemplateEp.PATCH, templateAId), 403, C002);
            expectError(templateEp(TemplateEp.ARCHIVE, templateAId), 403, C002);
        }

        @Test
        @DisplayName("AC-3: user_roles のみの ADMIN は GET 403 C002（従来どおり・404 に化けない）、PATCH 200、archive 204")
        void userRolesOnly管理者() throws Exception {
            setAuth(urAdminAId);
            expectError(templateEp(TemplateEp.GET, templateAId), 403, C002);
            expectStatus(templateEp(TemplateEp.PATCH, templateAId), 200);
            expectStatus(templateEp(TemplateEp.ARCHIVE, templateAId), 204);
        }

        @Test
        @DisplayName("許可主体: スコープADMINは GET 200・PATCH 200・archive 204（非回帰）")
        void スコープADMINは2xx() throws Exception {
            setAuth(adminAId);
            expectStatus(templateEp(TemplateEp.GET, templateAId), 200);
            expectStatus(templateEp(TemplateEp.PATCH, templateAId), 200);
            expectStatus(templateEp(TemplateEp.ARCHIVE, templateAId), 204);
        }

        @Test
        @DisplayName("SYSTEM_ADMIN: 非メンバーは3EPとも403 C002、メンバーは GET 200・PATCH/archive 403 C002（是正前どおり）")
        void SYSTEM_ADMINは是正前どおり() throws Exception {
            setAuth(systemAdminId);
            for (TemplateEp ep : TemplateEp.values()) {
                expectError(templateEp(ep, templateAId), 403, C002);
            }
            expectError(templateEp(TemplateEp.GET, MISSING_ID), 404, R313);
            setAuth(memberSystemAdminId);
            expectStatus(templateEp(TemplateEp.GET, templateAId), 200);
            expectError(templateEp(TemplateEp.PATCH, templateAId), 403, C002);
            expectError(templateEp(TemplateEp.ARCHIVE, templateAId), 403, C002);
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(TemplateEp.class)
        @DisplayName("アーカイブ済みテンプレートは管理者にも部外者にも不在と同一の404 R313")
        void アーカイブ済みは不在と同一(TemplateEp ep) throws Exception {
            for (Long actor : List.of(adminAId, outsiderId)) {
                setAuth(actor);
                assertSameAsMissing(templateEp(ep, templateArchivedAId), templateEp(ep, MISSING_ID), 404, R313);
            }
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(TemplateEp.class)
        @DisplayName("AC-10: ID 0・負数・Long.MAXは不在と同じ404 R313、非数値は400")
        void ID境界(TemplateEp ep) throws Exception {
            for (Long actor : List.of(adminAId, outsiderId)) {
                setAuth(actor);
                for (String id : BOUNDARY_IDS) {
                    expectError(templateEpRaw(ep, id), 404, R313);
                }
                expectStatus(templateEpRaw(ep, "abc"), 400);
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 4. from-template（C3）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("4. POST /{teams|organizations}/{id}/recruitment-listings/from-template（C3）")
    class CreateFromTemplate {

        @Test
        @DisplayName("C3: パス scope の ADMIN が他スコープの実在テンプレートを渡すと、不在IDと完全一致の404 R313（R314 と割れない）")
        void 他スコープの実在テンプレートは不在と同一() throws Exception {
            setAuth(adminAId);
            assertSameAsMissing(fromTemplateTeam(teamAId, templateBId), fromTemplateTeam(teamAId, MISSING_ID),
                    404, R313);
            assertSameAsMissing(fromTemplateTeam(teamAId, templateOrgId), fromTemplateTeam(teamAId, MISSING_ID),
                    404, R313);
            setAuth(orgAdminId);
            assertSameAsMissing(fromTemplateOrg(orgId, templateAId), fromTemplateOrg(orgId, MISSING_ID), 404, R313);
        }

        @Test
        @DisplayName("AC-11: 他スコープの実在テンプレートで 404 のとき募集は作られない")
        void 越境で募集を作らない() throws Exception {
            long before = count("SELECT COUNT(*) FROM recruitment_listings");
            setAuth(adminAId);
            mockMvc.perform(fromTemplateTeam(teamAId, templateBId)).andReturn();
            assertThat(count("SELECT COUNT(*) FROM recruitment_listings")).isEqualTo(before);
        }

        @Test
        @DisplayName("アーカイブ済みテンプレートは不在と同一の404 R313")
        void アーカイブ済みは不在と同一() throws Exception {
            setAuth(adminAId);
            assertSameAsMissing(fromTemplateTeam(teamAId, templateArchivedAId), fromTemplateTeam(teamAId, MISSING_ID),
                    404, R313);
        }

        @Test
        @DisplayName("パス scope の非管理者・他チームADMIN・SYSTEM_ADMIN は templateId に依らず403 C002のまま")
        void パスscopeの認可は従来どおり() throws Exception {
            for (Long actor : List.of(memberAId, adminBId, systemAdminId, memberSystemAdminId)) {
                setAuth(actor);
                expectError(fromTemplateTeam(teamAId, templateAId), 403, C002);
                expectError(fromTemplateTeam(teamAId, MISSING_ID), 403, C002);
            }
        }

        @Test
        @DisplayName("AC-9: templateId null は400（判定前に止まる）")
        void templateId_nullは400() throws Exception {
            setAuth(adminAId);
            expectStatus(fromTemplateTeam(teamAId, null), 400);
        }

        @Test
        @DisplayName("許可主体: 自スコープのテンプレートからの作成は201（非回帰）")
        void 自スコープは201() throws Exception {
            setAuth(adminAId);
            expectStatus(fromTemplateTeam(teamAId, templateAId), 201);
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 5. no-show の本人申立
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("5. POST /recruitment/no-shows/{id}/dispute（記録の本人のみ）")
    class Dispute {

        @Test
        @DisplayName("AC-1: 本人以外（同チームADMIN・user_roles のみADMIN・他チームADMIN・部外者・SYSTEM_ADMIN 2 種）は不在と同一の404 R309")
        void 本人以外は不在と同一() throws Exception {
            for (Long actor : List.of(adminAId, urAdminAId, adminBId, outsiderId, systemAdminId,
                    memberSystemAdminId)) {
                setAuth(actor);
                assertSameAsMissing(dispute(noShowAId), dispute(MISSING_ID), 404, R309);
            }
        }

        @Test
        @DisplayName("AC-7: 申立済みの記録でも本人以外には不在と同一の404（409 を返さない）。本人には409 R311")
        void 申立済みの状態は本人以外に漏れない() throws Exception {
            setAuth(adminAId);
            assertSameAsMissing(dispute(noShowDisputedAId), dispute(MISSING_ID), 404, R309);
            setAuth(memberAId);
            expectError(dispute(noShowDisputedAId), 409, R311);
        }

        @Test
        @DisplayName("AC-11: 本人以外の申立で記録は全列そのまま")
        void 本人以外でDB不変() throws Exception {
            String before = row("recruitment_no_show_records", noShowAId);
            setAuth(adminAId);
            mockMvc.perform(dispute(noShowAId)).andReturn();
            assertThat(row("recruitment_no_show_records", noShowAId)).isEqualTo(before);
        }

        @Test
        @DisplayName("許可主体: 記録の本人は200（非回帰）")
        void 本人は200() throws Exception {
            setAuth(memberAId);
            expectStatus(dispute(noShowAId), 200);
        }

        @Test
        @DisplayName("AC-10: ID 0・負数・Long.MAXは不在と同じ404 R309、非数値は400")
        void ID境界() throws Exception {
            setAuth(memberAId);
            for (String id : BOUNDARY_IDS) {
                expectError(disputeRaw(id), 404, R309);
            }
            expectStatus(disputeRaw("abc"), 400);
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 6. 参加申込（可視性判定を状態判定より前へ）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("6. POST /recruitment-listings/{id}/applications（申込）")
    class Apply {

        @Test
        @DisplayName("AC-7: 部外者・他チームADMINには SCOPE_ONLY の OPEN/DRAFT/締切超過/CANCELLED が不在と同一の404 R001（状態が漏れない）")
        void 非公開物への申込は状態に依らず不在と同一() throws Exception {
            for (Long actor : List.of(outsiderId, adminBId)) {
                setAuth(actor);
                for (Long listing : List.of(listingAId, listingDraftAId, listingDeadlinePassedAId,
                        listingCancelledAId)) {
                    assertSameAsMissing(apply(listing), apply(MISSING_ID), 404, R001);
                }
            }
        }

        @Test
        @DisplayName("AC-11: 越境の申込で参加者は作られない")
        void 越境で参加者を作らない() throws Exception {
            long before = count("SELECT COUNT(*) FROM recruitment_participants");
            setAuth(outsiderId);
            for (Long listing : List.of(listingAId, listingDraftAId, listingDeadlinePassedAId, listingCancelledAId)) {
                mockMvc.perform(apply(listing)).andReturn();
            }
            assertThat(count("SELECT COUNT(*) FROM recruitment_participants")).isEqualTo(before);
        }

        @Test
        @DisplayName("同スコープメンバー: OPEN は201、締切超過は R101（閲覧できる者には状態を返す・非回帰）")
        void 同スコープメンバーは従来どおり() throws Exception {
            setAuth(orgMemberId);
            expectStatus(apply(listingOrgId), 201);
            setAuth(memberAId);
            ErrorView view = perform(apply(listingDeadlinePassedAId));
            assertThat(view.code()).as(String.valueOf(view)).isEqualTo(R101);
        }

        @Test
        @DisplayName("殿の判断(a): 同スコープメンバーが DRAFT の募集に申し込むと、是正前の409 R103のまま（可視性で落とすのはスコープ外の者だけ）")
        void 同スコープメンバーのDRAFT申込は409_R103() throws Exception {
            setAuth(memberAId);
            expectError(apply(listingDraftAId), 409, R103);
            setAuth(adminAId);
            expectError(apply(listingDraftAId), 409, R103);
            setAuth(urAdminAId);
            expectError(apply(listingDraftAId), 409, R103);
        }

        @Test
        @DisplayName("P2: 同スコープ一般メンバーが F00 の拒否する OPEN（CUSTOM_TEMPLATE）に申し込むと、是正前の403 V001のまま。参加者は作られない")
        void 同スコープメンバーの可視性拒否は403_V001() throws Exception {
            long before = count("SELECT COUNT(*) FROM recruitment_participants");
            setAuth(memberAId);
            expectError(apply(listingCustomAId), 403, V001);
            assertThat(count("SELECT COUNT(*) FROM recruitment_participants")).isEqualTo(before);
        }

        @Test
        @DisplayName("AC-4: 公開物（PUBLIC OPEN）は部外者も201（非回帰）")
        void 公開物は部外者も201() throws Exception {
            setAuth(outsiderId);
            expectStatus(apply(listingPublicAId), 201);
        }

        @Test
        @DisplayName("SYSTEM_ADMIN（非メンバー）は是正前どおり SCOPE_ONLY OPEN に201（F00 の SystemAdmin 高速パス）")
        void SYSTEM_ADMINは是正前どおり201() throws Exception {
            setAuth(systemAdminId);
            expectStatus(apply(listingAId), 201);
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // リクエスト
    // ═════════════════════════════════════════════════════════════════════

    private RequestBuilder listingEp(ListingEp ep, Long listingId) {
        Long participant = attendParticipantByListing.getOrDefault(listingId,
                attendParticipantByListing.get(listingAId));
        return listingEpRaw(ep, String.valueOf(listingId), participant);
    }

    private RequestBuilder listingEpRaw(ListingEp ep, String id, Long participantId) {
        String base = "/api/v1/recruitment-listings/" + id;
        return switch (ep) {
            case UPDATE -> patch(base).contentType(MediaType.APPLICATION_JSON).content(json(Map.of("title", "W5 編集")));
            case PUBLISH -> post(base + "/publish");
            case CANCEL -> post(base + "/cancel").contentType(MediaType.APPLICATION_JSON)
                    .content(json(Map.of("reason", "W5 中止")));
            case ARCHIVE -> post(base + "/archive");
            case DIST_GET -> get(base + "/distribution-targets");
            case DIST_PUT -> put(base + "/distribution-targets").contentType(MediaType.APPLICATION_JSON)
                    .content(json(Map.of("targetTypes", List.of(RecruitmentDistributionTargetType.MEMBERS.name()))));
            case PARTICIPANTS -> get(base + "/participants");
            case ATTEND -> patch(base + "/participants/" + participantId + "/attend");
        };
    }

    private RequestBuilder getListing(Long id) {
        return get("/api/v1/recruitment-listings/{id}", id);
    }

    private RequestBuilder templateEp(TemplateEp ep, Long id) {
        return templateEpRaw(ep, String.valueOf(id));
    }

    private RequestBuilder templateEpRaw(TemplateEp ep, String id) {
        String base = "/api/v1/recruitment-templates/" + id;
        return switch (ep) {
            case GET -> get(base);
            case PATCH -> patch(base).contentType(MediaType.APPLICATION_JSON)
                    .content(json(Map.of("templateName", "W5 編集")));
            case ARCHIVE -> post(base + "/archive");
        };
    }

    private RequestBuilder fromTemplateTeam(Long teamId, Long templateId) {
        return post("/api/v1/teams/{teamId}/recruitment-listings/from-template", teamId)
                .contentType(MediaType.APPLICATION_JSON).content(json(fromTemplateBody(templateId)));
    }

    private RequestBuilder fromTemplateOrg(Long organizationId, Long templateId) {
        return post("/api/v1/organizations/{orgId}/recruitment-listings/from-template", organizationId)
                .contentType(MediaType.APPLICATION_JSON).content(json(fromTemplateBody(templateId)));
    }

    private Map<String, Object> fromTemplateBody(Long templateId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("templateId", templateId);
        body.put("startAt", LocalDateTime.now().plusDays(30).withNano(0).toString());
        return body;
    }

    private RequestBuilder dispute(Long id) {
        return disputeRaw(String.valueOf(id));
    }

    private RequestBuilder disputeRaw(String id) {
        return post("/api/v1/recruitment/no-shows/" + id + "/dispute")
                .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("reason", "W5 異議")));
    }

    private RequestBuilder apply(Long listingId) {
        return post("/api/v1/recruitment-listings/{id}/applications", listingId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("participantType", RecruitmentParticipantType.USER.name())));
    }

    private String json(Object body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 判定ヘルパー
    // ═════════════════════════════════════════════════════════════════════

    private void assertSameAsMissing(RequestBuilder real, RequestBuilder missing, int expectedStatus,
                                     String expectedCode) throws Exception {
        ErrorView realView = perform(real);
        ErrorView missingView = perform(missing);
        assertThat(realView).as("実在IDへの応答と不在IDへの応答が一致すること").isEqualTo(missingView);
        assertThat(realView.status()).isEqualTo(expectedStatus);
        assertThat(realView.code()).isEqualTo(expectedCode);
        assertThat(realView.message()).isNotBlank();
    }

    private void expectError(RequestBuilder request, int expectedStatus, String expectedCode) throws Exception {
        ErrorView view = perform(request);
        assertThat(view.status()).as(String.valueOf(view)).isEqualTo(expectedStatus);
        assertThat(view.code()).as(String.valueOf(view)).isEqualTo(expectedCode);
    }

    private void expectStatus(RequestBuilder request, int expectedStatus) throws Exception {
        ErrorView view = perform(request);
        assertThat(view.status()).as(String.valueOf(view)).isEqualTo(expectedStatus);
    }

    private ErrorView perform(RequestBuilder request) throws Exception {
        MvcResult result = mockMvc.perform(request).andReturn();
        String body = result.getResponse().getContentAsString();
        JsonNode error = body.isBlank() ? null : objectMapper.readTree(body).path("error");
        return new ErrorView(result.getResponse().getStatus(),
                error == null ? null : error.path("code").asText(null),
                error == null ? null : error.path("message").asText(null));
    }

    private record ErrorView(int status, String code, String message) {
    }

    /** 募集とその配下（参加者・参加履歴・配信対象）を全列そのまま文字列にする。 */
    private String listingFootprint(Long listingId) {
        return String.join("\n",
                rows("SELECT * FROM recruitment_listings WHERE id = " + listingId),
                rows("SELECT * FROM recruitment_participants WHERE listing_id = " + listingId + " ORDER BY id"),
                rows("SELECT * FROM recruitment_participant_history WHERE listing_id = " + listingId + " ORDER BY id"),
                rows("SELECT * FROM recruitment_distribution_targets WHERE listing_id = " + listingId
                        + " ORDER BY id"));
    }

    private String row(String table, Long id) {
        return rows("SELECT * FROM " + table + " WHERE id = " + id);
    }

    private String rows(String sql) {
        em.flush();
        em.clear();
        @SuppressWarnings("unchecked")
        List<Object> rows = em.createNativeQuery(sql).getResultList();
        return rows.stream().map(r -> r instanceof Object[] cols ? Arrays.deepToString(cols) : String.valueOf(r))
                .collect(Collectors.joining(",", "[", "]"));
    }

    private long count(String sql) {
        em.flush();
        return ((Number) em.createNativeQuery(sql).getSingleResult()).longValue();
    }

    private void setAuth(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
    }

    // ═════════════════════════════════════════════════════════════════════
    // フィクスチャ
    // ═════════════════════════════════════════════════════════════════════

    private Long insertListing(RecruitmentScopeType scopeType, Long scopeId, RecruitmentVisibility visibility,
                               RecruitmentListingStatus status, Long createdBy, LocalDateTime deadline) {
        LocalDateTime start = LocalDateTime.now().plusDays(30);
        return listingRepository.save(RecruitmentListingEntity.builder()
                .scopeType(scopeType)
                .scopeId(scopeId)
                .categoryId(categoryId)
                .title("W5LIST 募集")
                .participationType(RecruitmentParticipationType.INDIVIDUAL)
                .startAt(start)
                .endAt(start.plusHours(2))
                .applicationDeadline(deadline)
                .autoCancelAt(deadline.minusDays(1))
                .capacity(10)
                .minCapacity(1)
                .status(status)
                .visibility(visibility)
                .location("W5LIST 会場")
                .createdBy(createdBy)
                .build()).getId();
    }

    private Long insertParticipant(Long listingId, Long userId, RecruitmentParticipantStatus status) {
        return participantRepository.save(RecruitmentParticipantEntity.builder()
                .listingId(listingId)
                .participantType(RecruitmentParticipantType.USER)
                .userId(userId)
                .appliedBy(userId)
                .status(status)
                // 申込のレート制限（直近1分に5件以上で拒否）に、フィクスチャの行が数えられないよう過去にする
                .appliedAt(LocalDateTime.now().minusDays(1))
                .build()).getId();
    }

    private void insertDistributionTarget(Long listingId, RecruitmentDistributionTargetType type) {
        distributionTargetRepository.save(RecruitmentDistributionTargetEntity.builder()
                .listingId(listingId)
                .targetType(type)
                .build());
    }

    private Long insertTemplate(RecruitmentScopeType scopeType, Long scopeId) {
        return templateRepository.save(RecruitmentTemplateEntity.builder()
                .scopeType(scopeType)
                .scopeId(scopeId)
                .categoryId(categoryId)
                .templateName("W5LIST テンプレート")
                .title("W5LIST テンプレート募集")
                .defaultLocation("W5LIST 会場")
                .createdBy(adminAId)
                .build()).getId();
    }

    private Long insertNoShow(Long listingId, Long participantId, Long userId) {
        return noShowRepository.save(RecruitmentNoShowRecordEntity.builder()
                .participantId(participantId)
                .listingId(listingId)
                .userId(userId)
                .reason(NoShowReason.ADMIN_MARKED)
                .recordedBy(adminAId)
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
                                + "VALUES (:email, 'W5LIST', 'テスト', 'W5LIST テスト', 'ACTIVE', "
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
                                + "CONCAT('w5l-', LEFT(REPLACE(UUID(),'-',''),8)), NOW(), NOW())")
                .setParameter("name", name)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM teams WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult()).longValue();
    }

    private Long insertOrganization(String name) {
        em.createNativeQuery(
                        "INSERT INTO organizations (name, org_type, visibility, hierarchy_visibility, "
                                + "supporter_enabled, version, slug, created_at, updated_at) "
                                + "VALUES (:name, 'OTHER', 'PUBLIC', 'NONE', 1, 0, "
                                + "CONCAT('w5l-o-', LEFT(REPLACE(UUID(),'-',''),8)), NOW(), NOW())")
                .setParameter("name", name)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM organizations WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult()).longValue();
    }
}
