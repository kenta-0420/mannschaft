package com.mannschaft.app.recruitment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.market.MarketErrorCode;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * recruitment の金銭・制裁 6 EP の認可契約テスト（CMP-260923-0954 W4・存在オラクル是正）。
 *
 * <p>対象 EP: キャンセル料の免除（waive）・ペナルティ解除（lift）・申込確定（participant confirm）・
 * キャンセルポリシーの詳細／編集／論理削除。越境（スコープに所属しない利用者）が実在 ID を叩いた応答と、
 * 同じ利用者が不在 ID を叩いた応答を status・error.code・error.message まで一致させる。
 * 同一スコープ内の権限不足は従来どおり 403、SYSTEM_ADMIN は是正前の挙動を固定する（マスター裁可 2026-09-30）。</p>
 *
 * <h2>EP 別許可主体表（本クラスが固定する契約。左: 是正前 / 右: 是正後）</h2>
 * <pre>
 * ■ POST /recruitment-cancellation-records/{id}/waive（受取先が個人の記録。募集は teamA・record.teamId=null）
 *   部外者・他チームADMIN                 | 403 C002 → 404 C005（不在と完全一致）
 *   同スコープ一般メンバー                | 403 C002 → 403 C002
 *   スコープ管理者（受取側でない）        | 403 C002 → 403 C002（チーム ADMIN であるだけでは免除不可）
 *   user_roles のみの ADMIN（在籍なし）   | 403 C002 → 403 C002（AC-3。404 に化けない）
 *   債務者本人（非在籍）                  | 403 C002 → 403 C002
 *   受取側の精算管理者（受取本人・受取チーム ADMIN） | 200 → 200
 *   非メンバー SYSTEM_ADMIN               | 200 → 200（是正前から許可）
 *   メンバー SYSTEM_ADMIN                 | 200 → 200
 *   対象不在・0・負数・MAX                | 404 C005 → 404 C005 / 非数値 400
 *   親（募集）だけ論理削除済み・部外者    | 403 C002 → 404 C005
 *   ORG 募集の在籍者 / 部外者             | 403 / 403 → 403 / 404 C005
 *   PERSONAL 募集の債務者 / 受取本人 / 部外者 | 403 / 200 / 403 → 403 / 200 / 404 C005（500 にならない）
 *   PAID・部外者 / 受取管理者             | 403 / 409 R315 → 404 C005 / 409 R315（状態判定は認可の後）
 *   WAIVED・部外者 / 受取管理者           | 403 / 200 → 404 C005 / 200（冪等）
 *
 * ■ POST /scopes/{t}/{id}/penalties/{penaltyId}/lift（発動元設定 = teamA）
 *   部外者・他チームADMIN                 | 403 C002 → 404 R310
 *   同スコープ一般メンバー                | 403 C002 → 403 C002
 *   スコープ管理者・user_roles のみ ADMIN/DEPUTY | 200 → 200
 *   非メンバー SYSTEM_ADMIN／メンバー SYSTEM_ADMIN | 403 C002 → 403 C002（新規許可しない）
 *   対象不在・0・負数・MAX                | 404 R310 → 404 R310 / 非数値 400
 *   解除済み・期限切れ（部外者）          | 409 R100 → 404 R310（状態判定は認可の後）
 *   解除済み（スコープ管理者）            | 409 R100 → 409 R100
 *   親（発動元設定）だけ不在（部外者）    | 400 R312 → 404 R310
 *   パスの scope と設定の scope が不一致  | 200（管理者）/403 → 404 R310
 *   設定が PERSONAL・GLOBAL（部外者）     | 403 C002 → 404 R310（500 にならない）
 *
 * ■ POST /recruitment-listings/{listingId}/participants/{participantId}/confirm（募集 = teamA）
 *   部外者・他チームADMIN・応募者本人（非在籍） | 403 C002 → 404 R001
 *   公開中 PUBLIC 募集の参加者・他チームADMIN | 403 C002 → 404 R001（参加者は公開物ではない）
 *   同スコープ一般メンバー                | 403 C002 → 403 C002
 *   スコープ管理者・user_roles のみ ADMIN | 200 → 200
 *   非メンバー SYSTEM_ADMIN／メンバー SYSTEM_ADMIN | 403 C002 → 403 C002
 *   対象不在・0・負数・MAX                | 404 R001 → 404 R001 / 非数値 400
 *   パスの listingId と participant の募集が不一致 | 200（管理者）/403 → 404 R001
 *   PERSONAL・GLOBAL 募集の参加者（誰でも）| 404 MARKET_404 → 404 R001（コード割れの解消）
 *   CONFIRMED 済み（部外者 / 管理者）     | 403 / 409 R100 → 404 R001 / 409 R100
 *   親（募集）だけ論理削除済み（管理者・部外者） | 404 R001 / 404 R001 → 404 R001
 *
 * ■ GET / PATCH / POST archive /cancellation-policies/{id}（ポリシー = teamA）
 *   部外者・他チームADMIN                 | 403 C002 → 404 R001
 *   同スコープ一般メンバー                | 403 C002 → 403 C002
 *   スコープ管理者・user_roles のみ ADMIN | 2xx → 2xx
 *   非メンバー SYSTEM_ADMIN／メンバー SYSTEM_ADMIN | 403 C002 → 403 C002
 *   対象不在・0・負数・MAX・論理削除済み  | 404 R001 → 404 R001 / 非数値 400
 *   PATCH: テンプレートでないポリシー（部外者 / 管理者） | 403 / 400 R302 → 404 R001 / 400 R302
 *   archive 済みへの再 archive（管理者）  | 404 R001 → 404 R001
 *   ※ PERSONAL スコープのポリシーを作る経路は無い（作成は TeamCancellationPolicyController の TEAM 固定と、
 *     元ポリシーのスコープを写す RecruitmentTemplateService#deepCopyPolicyIfNeeded のみ）ため本表に含めない。
 * </pre>
 *
 * <p>競合（認可の後・tx の前の削除）・FOR UPDATE の順序・クエリ回数は
 * {@code RecruitmentMoneyFacadeRaceAndQueryIT}、Facade 構成は {@code RecruitmentMoneyTxFacadeArchTest} が固定する。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("recruitment 金銭・制裁 6EP 認可契約テスト（W4 存在オラクル）")
class RecruitmentMoneyPenaltyScopeContractIT extends AbstractMySqlIntegrationTest {

    private static final long MISSING_ID = 987_654_321L;
    private static final List<String> BOUNDARY_IDS = List.of("0", "-1", String.valueOf(Long.MAX_VALUE));

    private static final String C002 = CommonErrorCode.COMMON_002.getCode();
    private static final String C005 = CommonErrorCode.COMMON_005.getCode();
    private static final String R001 = RecruitmentErrorCode.LISTING_NOT_FOUND.getCode();
    private static final String R100 = RecruitmentErrorCode.INVALID_STATE_TRANSITION.getCode();
    private static final String R302 = RecruitmentErrorCode.INVALID_CANCELLATION_POLICY.getCode();
    private static final String R310 = RecruitmentErrorCode.PENALTY_NOT_FOUND.getCode();
    private static final String R315 = RecruitmentErrorCode.CANCELLATION_FEE_ALREADY_PAID.getCode();

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

    /** 外部境界（Stripe）のみ差し替える。既存の免除 IT と同じ構成にしてコンテキストを共有する。 */
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
    private Long urDeputyAId;
    private Long adminBId;
    private Long outsiderId;
    private Long systemAdminId;
    private Long memberSystemAdminId;
    private Long applicantId;
    private Long debtorId;
    private Long debtor2Id;
    private Long individualPayeeId;
    private Long orgMemberId;
    private Long personalOwnerId;

    // ---- 募集・参加者 ----
    private Long listingAId;
    private Long listingBId;
    private Long listingPublicAId;
    private Long listingOrgId;
    private Long listingPersonalId;
    private Long listingGlobalId;
    private Long listingDeletedAId;
    private Long listingHiddenAId;
    private Long pAppliedAId;
    private Long pAppliedBId;
    private Long pPublicAId;
    private Long pPersonalId;
    private Long pGlobalId;
    private Long pConfirmedAId;
    private Long pDeletedListingId;
    private Long pHiddenListingId;

    // ---- ペナルティ ----
    private Long penaltyAId;
    private Long penaltyLiftedAId;
    private Long penaltyExpiredAId;
    private Long penaltyOrphanId;
    private Long penaltyPersonalId;
    private Long penaltyGlobalSettingId;

    // ---- ポリシー ----
    private Long policyAId;
    private Long policyNonTemplateAId;
    private Long policyArchivedAId;

    // ---- キャンセル記録 ----
    private Long recUserPayeeId;
    private Long recTeamPayeeId;
    private Long recOrgId;
    private Long recPersonalId;
    private Long recPaidId;
    private Long recWaivedId;
    private Long recDeletedListingId;
    private Long recHiddenListingId;

    @BeforeEach
    void setUp() {
        teamAId = insertTeam("W4MONEY チームA");
        teamBId = insertTeam("W4MONEY チームB");
        orgId = insertOrganization("W4MONEY 組織");

        adminAId = insertUser("w4m-admin-a@example.com");
        memberAId = insertUser("w4m-member-a@example.com");
        urAdminAId = insertUser("w4m-ur-admin-a@example.com");
        urDeputyAId = insertUser("w4m-ur-deputy-a@example.com");
        adminBId = insertUser("w4m-admin-b@example.com");
        outsiderId = insertUser("w4m-outsider@example.com");
        systemAdminId = insertUser("w4m-sysadmin@example.com");
        memberSystemAdminId = insertUser("w4m-member-sysadmin@example.com");
        applicantId = insertUser("w4m-applicant@example.com");
        debtorId = insertUser("w4m-debtor@example.com");
        debtor2Id = insertUser("w4m-debtor2@example.com");
        individualPayeeId = insertUser("w4m-individual-payee@example.com");
        orgMemberId = insertUser("w4m-org-member@example.com");
        personalOwnerId = insertUser("w4m-personal-owner@example.com");

        MembershipTestHelper.insertMembership(em, adminAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminAId, "ADMIN", teamAId, null);
        MembershipTestHelper.insertMembership(em, memberAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, urAdminAId, "ADMIN", teamAId, null);
        MembershipTestHelper.insertUserRole(em, urDeputyAId, "DEPUTY_ADMIN", teamAId, null);
        MembershipTestHelper.insertMembership(em, adminBId, ScopeType.TEAM, teamBId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminBId, "ADMIN", teamBId, null);
        MembershipTestHelper.insertUserRole(em, systemAdminId, "SYSTEM_ADMIN", null, null);
        MembershipTestHelper.insertMembership(em, memberSystemAdminId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, memberSystemAdminId, "SYSTEM_ADMIN", null, null);
        MembershipTestHelper.insertMembership(em, orgMemberId, ScopeType.ORGANIZATION, orgId, RoleKind.MEMBER);
        grantManageRecruitmentsToAdmin();

        categoryId = categoryRepository.save(RecruitmentCategoryEntity.builder()
                .code("W4MONEY_TEST")
                .nameI18nKey("recruitment.category.w4moneyTest")
                .defaultParticipationType(RecruitmentParticipationType.INDIVIDUAL)
                .displayOrder(0)
                .isActive(true)
                .build()).getId();

        listingAId = insertListing(RecruitmentScopeType.TEAM, teamAId, RecruitmentVisibility.SCOPE_ONLY, adminAId);
        listingBId = insertListing(RecruitmentScopeType.TEAM, teamBId, RecruitmentVisibility.SCOPE_ONLY, adminBId);
        listingPublicAId = insertListing(RecruitmentScopeType.TEAM, teamAId, RecruitmentVisibility.PUBLIC, adminAId);
        listingOrgId = insertListing(RecruitmentScopeType.ORGANIZATION, orgId, RecruitmentVisibility.SCOPE_ONLY,
                orgMemberId);
        listingPersonalId = insertListing(RecruitmentScopeType.PERSONAL, personalOwnerId,
                RecruitmentVisibility.SCOPE_ONLY, personalOwnerId);
        // GLOBAL はペナルティ専用の値だが、DB 上は募集にも入りうる。scopeId に実在チームを入れ、
        // 種別を無視してチームとして判定する誤りも検出できるようにする。
        listingGlobalId = insertListing(RecruitmentScopeType.GLOBAL, teamAId, RecruitmentVisibility.SCOPE_ONLY,
                adminAId);
        listingDeletedAId = insertListing(RecruitmentScopeType.TEAM, teamAId, RecruitmentVisibility.SCOPE_ONLY,
                adminAId);

        pAppliedAId = insertParticipant(listingAId, applicantId, RecruitmentParticipantStatus.APPLIED);
        pAppliedBId = insertParticipant(listingBId, applicantId, RecruitmentParticipantStatus.APPLIED);
        pPublicAId = insertParticipant(listingPublicAId, applicantId, RecruitmentParticipantStatus.APPLIED);
        pPersonalId = insertParticipant(listingPersonalId, applicantId, RecruitmentParticipantStatus.APPLIED);
        pGlobalId = insertParticipant(listingGlobalId, applicantId, RecruitmentParticipantStatus.APPLIED);
        pConfirmedAId = insertParticipant(listingAId, memberAId, RecruitmentParticipantStatus.CONFIRMED);
        pDeletedListingId = insertParticipant(listingDeletedAId, applicantId, RecruitmentParticipantStatus.APPLIED);
        // モデレーション非表示の募集（moderation_hidden_at のみ。deleted_at は NULL）
        listingHiddenAId = insertListing(RecruitmentScopeType.TEAM, teamAId, RecruitmentVisibility.SCOPE_ONLY,
                adminAId);
        pHiddenListingId = insertParticipant(listingHiddenAId, applicantId, RecruitmentParticipantStatus.APPLIED);

        // ---- ペナルティ ----
        Long settingAId = insertSetting(RecruitmentScopeType.TEAM, teamAId);
        Long settingPersonalId = insertSetting(RecruitmentScopeType.PERSONAL, personalOwnerId);
        Long settingGlobalId = insertSetting(RecruitmentScopeType.GLOBAL, teamAId);
        Long settingOrphanId = insertSetting(RecruitmentScopeType.ORGANIZATION, orgId);
        penaltyAId = insertPenalty(memberAId, RecruitmentScopeType.TEAM, teamAId, settingAId, false);
        penaltyLiftedAId = insertPenalty(memberAId, RecruitmentScopeType.TEAM, teamAId, settingAId, false);
        penaltyExpiredAId = insertPenalty(memberAId, RecruitmentScopeType.TEAM, teamAId, settingAId, true);
        penaltyOrphanId = insertPenalty(memberAId, RecruitmentScopeType.TEAM, teamAId, settingOrphanId, false);
        penaltyPersonalId = insertPenalty(applicantId, RecruitmentScopeType.PERSONAL, personalOwnerId,
                settingPersonalId, false);
        penaltyGlobalSettingId = insertPenalty(applicantId, RecruitmentScopeType.GLOBAL, null, settingGlobalId,
                false);

        // ---- ポリシー ----
        policyAId = insertPolicy(teamAId, true);
        policyNonTemplateAId = insertPolicy(teamAId, false);
        policyArchivedAId = insertPolicy(teamAId, true);

        // ---- キャンセル記録（記録 ↔ escrow は三つ組で結ぶ。record.teamId は個人参加なので null） ----
        UUID individualPayeeAccount = insertConnectAccount(ScopeKind.USER, individualPayeeId);
        UUID teamAAccount = insertConnectAccount(ScopeKind.TEAM, teamAId);
        UUID personalOwnerAccount = insertConnectAccount(ScopeKind.USER, personalOwnerId);
        recUserPayeeId = insertRecordWithEscrow(listingAId, debtorId, CancellationPaymentStatus.PENDING,
                ScopeKind.USER, individualPayeeAccount);
        recTeamPayeeId = insertRecordWithEscrow(listingPublicAId, debtorId, CancellationPaymentStatus.PENDING,
                ScopeKind.TEAM, teamAAccount);
        recOrgId = insertRecordWithEscrow(listingOrgId, debtorId, CancellationPaymentStatus.PENDING,
                ScopeKind.USER, individualPayeeAccount);
        recPersonalId = insertRecordWithEscrow(listingPersonalId, debtorId, CancellationPaymentStatus.PENDING,
                ScopeKind.USER, personalOwnerAccount);
        recPaidId = insertRecordWithEscrow(listingAId, debtor2Id, CancellationPaymentStatus.PAID,
                ScopeKind.TEAM, teamAAccount);
        recWaivedId = insertRecordWithEscrow(listingPublicAId, debtor2Id, CancellationPaymentStatus.WAIVED,
                ScopeKind.TEAM, teamAAccount);
        recDeletedListingId = insertRecordWithEscrow(listingDeletedAId, debtorId, CancellationPaymentStatus.PENDING,
                ScopeKind.USER, individualPayeeAccount);
        recHiddenListingId = insertRecordWithEscrow(listingHiddenAId, debtorId, CancellationPaymentStatus.PENDING,
                ScopeKind.USER, individualPayeeAccount);

        em.flush();
        // 状態の作り込み（ビルダーで表せないもの）
        em.createNativeQuery("UPDATE recruitment_user_penalties SET lifted_at = NOW(), lifted_by = :by, "
                        + "lift_reason = 'ADMIN_MANUAL' WHERE id = :id")
                .setParameter("by", adminAId).setParameter("id", penaltyLiftedAId).executeUpdate();
        em.createNativeQuery("DELETE FROM recruitment_penalty_settings WHERE id = :id")
                .setParameter("id", settingOrphanId).executeUpdate();
        em.createNativeQuery("UPDATE recruitment_cancellation_policies SET deleted_at = NOW() WHERE id = :id")
                .setParameter("id", policyArchivedAId).executeUpdate();
        em.createNativeQuery("UPDATE recruitment_listings SET deleted_at = NOW() WHERE id = :id")
                .setParameter("id", listingDeletedAId).executeUpdate();
        em.createNativeQuery("UPDATE recruitment_listings SET moderation_hidden_at = NOW() WHERE id = :id")
                .setParameter("id", listingHiddenAId).executeUpdate();
        em.flush();
        em.clear();
    }

    // ═════════════════════════════════════════════════════════════════════
    // 1. waive
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("1. POST /recruitment-cancellation-records/{id}/waive（キャンセル料の免除）")
    class Waive {

        @Test
        @DisplayName("AC-1: 部外者・他チームADMINは、実在IDと不在IDで応答が一致（404 COMMON_005）")
        void 越境は不在と同一応答() throws Exception {
            for (Long actor : List.of(outsiderId, adminBId)) {
                setAuth(actor);
                assertSameAsMissing(waive(recUserPayeeId), waive(MISSING_ID), 404, C005);
            }
        }

        @Test
        @DisplayName("AC-11: 越境 404 のとき記録は全列そのまま")
        void 越境でDB不変() throws Exception {
            String before = row("recruitment_cancellation_records", recUserPayeeId);
            setAuth(outsiderId);
            mockMvc.perform(waive(recUserPayeeId)).andReturn();
            assertThat(row("recruitment_cancellation_records", recUserPayeeId)).isEqualTo(before);
        }

        @Test
        @DisplayName("AC-2: 同スコープ一般メンバー・受取側でないチームADMIN・債務者本人は403 COMMON_002のまま")
        void 存在を知り得る者は403() throws Exception {
            for (Long actor : List.of(memberAId, adminAId, debtorId)) {
                setAuth(actor);
                expectError(waive(recUserPayeeId), 403, C002);
            }
        }

        @Test
        @DisplayName("AC-3: user_rolesのみのADMIN（在籍なし・受取側でない）は403 COMMON_002のまま（404に化けない）")
        void userRolesOnly管理者は403() throws Exception {
            setAuth(urAdminAId);
            expectError(waive(recUserPayeeId), 403, C002);
        }

        @Test
        @DisplayName("許可主体: 受取本人・受取チームADMIN・SYSTEM_ADMIN（非メンバー／メンバー）は200")
        void 許可主体は200() throws Exception {
            setAuth(individualPayeeId);
            expectStatus(waive(recUserPayeeId), 200);
            setAuth(adminAId);
            expectStatus(waive(recTeamPayeeId), 200);
            setAuth(systemAdminId);
            expectStatus(waive(recOrgId), 200);
            setAuth(memberSystemAdminId);
            expectStatus(waive(recPersonalId), 200);
        }

        @Test
        @DisplayName("SYSTEM_ADMIN（非メンバー）の不在IDは404 COMMON_005")
        void SYSTEM_ADMINの不在は404() throws Exception {
            setAuth(systemAdminId);
            expectError(waive(MISSING_ID), 404, C005);
        }

        @Test
        @DisplayName("ORG募集（record.teamId=null）: 組織の在籍者は403、部外者は不在と同一の404")
        void ORG募集のスコープ解決() throws Exception {
            setAuth(orgMemberId);
            expectError(waive(recOrgId), 403, C002);
            setAuth(outsiderId);
            assertSameAsMissing(waive(recOrgId), waive(MISSING_ID), 404, C005);
        }

        @Test
        @DisplayName("PERSONAL募集（record.teamId=null）: 債務者は403、受取本人は200、部外者は不在と同一の404（500にならない）")
        void PERSONAL募集のスコープ解決() throws Exception {
            setAuth(debtorId);
            expectError(waive(recPersonalId), 403, C002);
            setAuth(outsiderId);
            assertSameAsMissing(waive(recPersonalId), waive(MISSING_ID), 404, C005);
            setAuth(personalOwnerId);
            expectStatus(waive(recPersonalId), 200);
        }

        @Test
        @DisplayName("AC-7: PAID・WAIVED の状態は部外者に漏れない（不在と同一の404）。受取管理者には409・冪等200")
        void 状態判定は認可の後() throws Exception {
            setAuth(outsiderId);
            assertSameAsMissing(waive(recPaidId), waive(MISSING_ID), 404, C005);
            assertSameAsMissing(waive(recWaivedId), waive(MISSING_ID), 404, C005);
            setAuth(adminAId);
            expectError(waive(recPaidId), 409, R315);
            expectStatus(waive(recWaivedId), 200);
        }

        @Test
        @DisplayName("親（募集）だけ論理削除済みの記録も、部外者には不在と同一の404")
        void 親だけ不在は404() throws Exception {
            setAuth(outsiderId);
            assertSameAsMissing(waive(recDeletedListingId), waive(MISSING_ID), 404, C005);
        }

        /**
         * 殿の判断3（2026-10-02）: モデレーション非表示の募集にぶら下がる記録の免除は、是正前の挙動を維持する。
         *
         * <p><b>是正前の挙動と根拠</b>: 是正前の免除（{@code RecruitmentCancellationFeeWaiveService#waive}）は
         * {@code cancellationRecordRepository.findById} で記録だけを読み、募集（{@code RecruitmentListingEntity}）を
         * 一度も読まなかった。{@code RecruitmentListingEntity} の {@code @SQLRestriction}
         * （{@code deleted_at IS NULL AND moderation_hidden_at IS NULL}）が掛かるのは募集を引くときだけなので、
         * モデレーション非表示の募集の記録も受取側は免除できた（200）。是正後も tx のたどり直しは
         * 論理削除（{@code deleted_at}）だけを見る（{@code lockLiveListingIdIgnoringModeration}）ため、同じく 200 になる。
         * 論理削除済みの募集（{@code 親だけ不在は404}）とは区別される。</p>
         */
        @Test
        @DisplayName("殿の判断3: モデレーション非表示の募集の記録は是正前どおり免除できる（受取側 200）。部外者は404、在籍者は403")
        void モデレーション非表示の募集でも是正前どおり() throws Exception {
            setAuth(individualPayeeId);
            expectStatus(waive(recHiddenListingId), 200);
            assertThat(row("recruitment_cancellation_records", recHiddenListingId)).contains("WAIVED");
            // 拒否経路も是正前と同じ分け方（存在を知り得る者は403、部外者は不在と同一の404）
            setAuth(memberAId);
            expectError(waive(recHiddenListingId), 403, C002);
            setAuth(outsiderId);
            assertSameAsMissing(waive(recHiddenListingId), waive(MISSING_ID), 404, C005);
        }

        @Test
        @DisplayName("AC-10: ID 0・負数・Long.MAXは不在と同じ404、非数値は400")
        void ID境界() throws Exception {
            for (Long actor : List.of(adminAId, outsiderId)) {
                setAuth(actor);
                for (String id : BOUNDARY_IDS) {
                    expectError(waiveRaw(id), 404, C005);
                }
                expectStatus(waiveRaw("abc"), 400);
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 2. penalty lift
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("2. POST /scopes/{scopeType}/{scopeId}/penalties/{penaltyId}/lift（ペナルティ解除）")
    class Lift {

        @Test
        @DisplayName("AC-1: 部外者・他チームADMINは、実在IDと不在IDで応答が一致（404 RECRUITMENT_310）")
        void 越境は不在と同一応答() throws Exception {
            for (Long actor : List.of(outsiderId, adminBId)) {
                setAuth(actor);
                assertSameAsMissing(lift("TEAM", teamAId, penaltyAId), lift("TEAM", teamAId, MISSING_ID), 404, R310);
            }
            // 他チーム ADMIN が自チームのパスを付けても同じ
            setAuth(adminBId);
            assertSameAsMissing(lift("TEAM", teamBId, penaltyAId), lift("TEAM", teamBId, MISSING_ID), 404, R310);
        }

        @Test
        @DisplayName("AC-7: 解除済み・期限切れ・発動元設定欠落は、越境者には不在と同一の404（409/400を返さない）")
        void 状態と設定欠落は越境者に漏れない() throws Exception {
            setAuth(outsiderId);
            for (Long id : List.of(penaltyLiftedAId, penaltyExpiredAId, penaltyOrphanId)) {
                assertSameAsMissing(lift("TEAM", teamAId, id), lift("TEAM", teamAId, MISSING_ID), 404, R310);
            }
        }

        @Test
        @DisplayName("AC-11: 越境 404 のときペナルティは全列そのまま（解除されない）")
        void 越境でDB不変() throws Exception {
            String before = row("recruitment_user_penalties", penaltyAId);
            setAuth(outsiderId);
            mockMvc.perform(lift("TEAM", teamAId, penaltyAId)).andReturn();
            assertThat(row("recruitment_user_penalties", penaltyAId)).isEqualTo(before);
        }

        @Test
        @DisplayName("パスの scopeType/scopeId が発動元設定のスコープと不一致なら、管理者でも不在と同一の404でDB不変")
        void パスのスコープ不一致は404() throws Exception {
            String before = row("recruitment_user_penalties", penaltyAId);
            setAuth(adminAId);
            assertSameAsMissing(lift("TEAM", teamBId, penaltyAId), lift("TEAM", teamBId, MISSING_ID), 404, R310);
            assertSameAsMissing(lift("ORGANIZATION", teamAId, penaltyAId),
                    lift("ORGANIZATION", teamAId, MISSING_ID), 404, R310);
            assertThat(row("recruitment_user_penalties", penaltyAId)).isEqualTo(before);
        }

        @Test
        @DisplayName("発動元設定が PERSONAL・GLOBAL でも 500 にならず、部外者には不在と同一の404")
        void 設定がPERSONALやGLOBALでも500にならない() throws Exception {
            setAuth(outsiderId);
            assertSameAsMissing(lift("PERSONAL", personalOwnerId, penaltyPersonalId),
                    lift("PERSONAL", personalOwnerId, MISSING_ID), 404, R310);
            assertSameAsMissing(lift("GLOBAL", teamAId, penaltyGlobalSettingId),
                    lift("GLOBAL", teamAId, MISSING_ID), 404, R310);
        }

        @Test
        @DisplayName("AC-2: 同スコープ一般メンバー（ペナルティ本人）は403 COMMON_002のまま")
        void 同スコープ一般メンバーは403() throws Exception {
            setAuth(memberAId);
            expectError(lift("TEAM", teamAId, penaltyAId), 403, C002);
        }

        @Test
        @DisplayName("SYSTEM_ADMIN（非メンバー／メンバー）は是正前どおり403 COMMON_002（新規許可も404化もしない）")
        void SYSTEM_ADMINは従来どおり403() throws Exception {
            for (Long actor : List.of(systemAdminId, memberSystemAdminId)) {
                setAuth(actor);
                expectError(lift("TEAM", teamAId, penaltyAId), 403, C002);
            }
            setAuth(systemAdminId);
            expectError(lift("TEAM", teamAId, MISSING_ID), 404, R310);
        }

        @Test
        @DisplayName("許可主体: スコープ管理者・user_rolesのみのADMIN/DEPUTY_ADMINは200（AC-3）")
        void 許可主体は200() throws Exception {
            setAuth(urAdminAId);
            expectStatus(lift("TEAM", teamAId, penaltyAId), 200);
            setAuth(urDeputyAId);
            expectStatus(lift("TEAM", teamAId, penaltyExpiredAId), 409);
            setAuth(adminAId);
            expectError(lift("TEAM", teamAId, penaltyLiftedAId), 409, R100);
        }

        @Test
        @DisplayName("AC-10: ID 0・負数・Long.MAXは不在と同じ404、非数値（penaltyId・scopeId）は400")
        void ID境界() throws Exception {
            for (Long actor : List.of(adminAId, outsiderId)) {
                setAuth(actor);
                for (String id : BOUNDARY_IDS) {
                    expectError(liftRaw("TEAM", String.valueOf(teamAId), id), 404, R310);
                }
                expectStatus(liftRaw("TEAM", String.valueOf(teamAId), "abc"), 400);
                expectStatus(liftRaw("TEAM", "abc", String.valueOf(penaltyAId)), 400);
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 3. participant confirm
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("3. POST /recruitment-listings/{listingId}/participants/{participantId}/confirm（申込確定）")
    class Confirm {

        @Test
        @DisplayName("AC-1: 部外者・他チームADMIN・応募者本人（非在籍）は、実在IDと不在IDで応答が一致（404 RECRUITMENT_001）")
        void 越境は不在と同一応答() throws Exception {
            for (Long actor : List.of(outsiderId, adminBId, applicantId)) {
                setAuth(actor);
                assertSameAsMissing(confirm(listingAId, pAppliedAId), confirm(listingAId, MISSING_ID), 404, R001);
            }
        }

        @Test
        @DisplayName("公開中PUBLIC募集の参加者でも、越境の確定は不在と同一の404（参加者は公開物ではない）")
        void 公開募集の参加者も404() throws Exception {
            setAuth(adminBId);
            assertSameAsMissing(confirm(listingPublicAId, pPublicAId), confirm(listingPublicAId, MISSING_ID),
                    404, R001);
        }

        @Test
        @DisplayName("パスのlistingIdとparticipantの募集が不一致なら、管理者でも不在と同一の404でDB不変")
        void パスのlistingId不一致は404() throws Exception {
            String before = row("recruitment_participants", pAppliedAId);
            setAuth(adminAId);
            assertSameAsMissing(confirm(listingBId, pAppliedAId), confirm(listingBId, MISSING_ID), 404, R001);
            assertSameAsMissing(confirm(listingPublicAId, pAppliedAId), confirm(listingPublicAId, MISSING_ID),
                    404, R001);
            assertThat(row("recruitment_participants", pAppliedAId)).isEqualTo(before);
            // 他チーム ADMIN が自チームの募集 ID を付けて teamA の参加者を叩いても同じ
            setAuth(adminBId);
            assertSameAsMissing(confirm(listingBId, pAppliedAId), confirm(listingBId, MISSING_ID), 404, R001);
        }

        @Test
        @DisplayName("PERSONAL・GLOBAL募集の参加者は、所有者・部外者とも不在と同一の404 RECRUITMENT_001（MARKET_404と割れない）")
        void PERSONALとGLOBALは不在と同一() throws Exception {
            for (Long actor : List.of(personalOwnerId, outsiderId, adminAId)) {
                setAuth(actor);
                assertSameAsMissing(confirm(listingPersonalId, pPersonalId), confirm(listingPersonalId, MISSING_ID),
                        404, R001);
                assertSameAsMissing(confirm(listingGlobalId, pGlobalId), confirm(listingGlobalId, MISSING_ID),
                        404, R001);
            }
            // 旧コード（MARKET_404）がどこにも出ないこと
            setAuth(personalOwnerId);
            assertThat(perform(confirm(listingPersonalId, pPersonalId)).code())
                    .isNotEqualTo(MarketErrorCode.LISTING_NOT_FOUND.getCode());
        }

        @Test
        @DisplayName("AC-7: CONFIRMED済みは部外者には不在と同一の404、管理者には認可の後の409 RECRUITMENT_100")
        void 状態判定は認可の後() throws Exception {
            setAuth(outsiderId);
            assertSameAsMissing(confirm(listingAId, pConfirmedAId), confirm(listingAId, MISSING_ID), 404, R001);
            setAuth(adminAId);
            expectError(confirm(listingAId, pConfirmedAId), 409, R100);
        }

        @Test
        @DisplayName("親（募集）だけ論理削除済み: 管理者・部外者とも不在と同一の404")
        void 親だけ不在は404() throws Exception {
            for (Long actor : List.of(adminAId, outsiderId)) {
                setAuth(actor);
                assertSameAsMissing(confirm(listingDeletedAId, pDeletedListingId),
                        confirm(listingDeletedAId, MISSING_ID), 404, R001);
            }
        }

        /**
         * 殿の判断3（2026-10-02）: たどり直しで募集を見る他の EP（申込確定）も、モデレーション非表示の募集は是正前の挙動を維持する。
         *
         * <p><b>是正前の挙動と根拠</b>: 是正前の確定は {@code listingRepository.findByIdForUpdate}（JPQL）で募集を引いており、
         * {@code RecruitmentListingEntity} の {@code @SQLRestriction}（{@code moderation_hidden_at IS NULL} を含む）が
         * 掛かるため、モデレーション非表示の募集は「不在」として {@code LISTING_NOT_FOUND}(404) になっていた
         * （管理者でも）。是正後は認可前の解決・tx の読み直しともエンティティ経由なので同じ 404 になり、DB も変わらない。
         * 免除（記録だけを読んでいたので通る）とは、是正前の挙動がそもそも違う。</p>
         */
        @Test
        @DisplayName("殿の判断3: モデレーション非表示の募集の参加者は是正前どおり不在と同一の404（管理者でも）でDB不変")
        void モデレーション非表示の募集は是正前どおり404() throws Exception {
            String before = row("recruitment_participants", pHiddenListingId);
            for (Long actor : List.of(adminAId, outsiderId)) {
                setAuth(actor);
                assertSameAsMissing(confirm(listingHiddenAId, pHiddenListingId),
                        confirm(listingHiddenAId, MISSING_ID), 404, R001);
            }
            assertThat(row("recruitment_participants", pHiddenListingId)).isEqualTo(before);
        }

        @Test
        @DisplayName("AC-11: 越境 404 のとき参加者・募集（確定数）・履歴は変わらない")
        void 越境でDB不変() throws Exception {
            String participantBefore = row("recruitment_participants", pAppliedAId);
            String listingBefore = row("recruitment_listings", listingAId);
            long historyBefore = count("SELECT COUNT(*) FROM recruitment_participant_history WHERE participant_id = "
                    + pAppliedAId);
            setAuth(outsiderId);
            mockMvc.perform(confirm(listingAId, pAppliedAId)).andReturn();
            assertThat(row("recruitment_participants", pAppliedAId)).isEqualTo(participantBefore);
            assertThat(row("recruitment_listings", listingAId)).isEqualTo(listingBefore);
            assertThat(count("SELECT COUNT(*) FROM recruitment_participant_history WHERE participant_id = "
                    + pAppliedAId)).isEqualTo(historyBefore);
        }

        @Test
        @DisplayName("AC-2: 同スコープ一般メンバーは403 COMMON_002のまま")
        void 同スコープ一般メンバーは403() throws Exception {
            setAuth(memberAId);
            expectError(confirm(listingAId, pAppliedAId), 403, C002);
        }

        @Test
        @DisplayName("SYSTEM_ADMIN（非メンバー／メンバー）は是正前どおり403 COMMON_002")
        void SYSTEM_ADMINは従来どおり403() throws Exception {
            for (Long actor : List.of(systemAdminId, memberSystemAdminId)) {
                setAuth(actor);
                expectError(confirm(listingAId, pAppliedAId), 403, C002);
            }
            setAuth(systemAdminId);
            expectError(confirm(listingAId, MISSING_ID), 404, R001);
        }

        @Test
        @DisplayName("許可主体: スコープ管理者・user_rolesのみのADMINは200（AC-3）")
        void 許可主体は200() throws Exception {
            setAuth(urAdminAId);
            expectStatus(confirm(listingAId, pAppliedAId), 200);
            setAuth(adminAId);
            expectStatus(confirm(listingPublicAId, pPublicAId), 200);
        }

        @Test
        @DisplayName("AC-10: ID 0・負数・Long.MAXは不在と同じ404、非数値は400")
        void ID境界() throws Exception {
            for (Long actor : List.of(adminAId, outsiderId)) {
                setAuth(actor);
                for (String id : BOUNDARY_IDS) {
                    expectError(confirmRaw(String.valueOf(listingAId), id), 404, R001);
                }
                expectStatus(confirmRaw(String.valueOf(listingAId), "abc"), 400);
                expectStatus(confirmRaw("abc", String.valueOf(pAppliedAId)), 400);
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 4〜6. cancellation-policies
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("4〜6. GET / PATCH / POST archive /cancellation-policies/{id}")
    class Policy {

        @Test
        @DisplayName("AC-1: 部外者・他チームADMINは、GET・PATCH・archive のいずれも実在IDと不在IDで応答が一致（404 RECRUITMENT_001）")
        void 越境は不在と同一応答() throws Exception {
            for (Long actor : List.of(outsiderId, adminBId)) {
                setAuth(actor);
                assertSameAsMissing(policyGet(policyAId), policyGet(MISSING_ID), 404, R001);
                assertSameAsMissing(policyPatch(policyAId), policyPatch(MISSING_ID), 404, R001);
                assertSameAsMissing(policyArchive(policyAId), policyArchive(MISSING_ID), 404, R001);
                // テンプレートでないポリシー（管理者なら 400 R302）も越境者には不在と同一
                assertSameAsMissing(policyPatch(policyNonTemplateAId), policyPatch(MISSING_ID), 404, R001);
            }
        }

        @Test
        @DisplayName("AC-11: 越境 404 の PATCH で tier の delete/insert が走らず、ポリシーも変わらない。archive も論理削除されない")
        void 越境でDB不変() throws Exception {
            String policyBefore = row("recruitment_cancellation_policies", policyAId);
            String tiersBefore = tiers(policyAId);
            setAuth(outsiderId);
            mockMvc.perform(policyPatch(policyAId)).andReturn();
            mockMvc.perform(policyArchive(policyAId)).andReturn();
            assertThat(row("recruitment_cancellation_policies", policyAId)).isEqualTo(policyBefore);
            assertThat(tiers(policyAId)).isEqualTo(tiersBefore);
        }

        @Test
        @DisplayName("AC-2: 同スコープ一般メンバーは403 COMMON_002のまま")
        void 同スコープ一般メンバーは403() throws Exception {
            setAuth(memberAId);
            expectError(policyGet(policyAId), 403, C002);
            expectError(policyPatch(policyAId), 403, C002);
            expectError(policyArchive(policyAId), 403, C002);
        }

        @Test
        @DisplayName("SYSTEM_ADMIN（非メンバー／メンバー）は是正前どおり403 COMMON_002")
        void SYSTEM_ADMINは従来どおり403() throws Exception {
            for (Long actor : List.of(systemAdminId, memberSystemAdminId)) {
                setAuth(actor);
                expectError(policyGet(policyAId), 403, C002);
                expectError(policyPatch(policyAId), 403, C002);
                expectError(policyArchive(policyAId), 403, C002);
            }
            setAuth(systemAdminId);
            expectError(policyGet(MISSING_ID), 404, R001);
        }

        @Test
        @DisplayName("許可主体: スコープ管理者・user_rolesのみのADMINは2xx（AC-3）。テンプレートでないPATCHは認可の後の400 R302")
        void 許可主体は2xx() throws Exception {
            setAuth(urAdminAId);
            expectStatus(policyGet(policyAId), 200);
            expectStatus(policyPatch(policyAId), 200);
            setAuth(adminAId);
            expectError(policyPatch(policyNonTemplateAId), 400, R302);
            expectStatus(policyArchive(policyAId), 204);
        }

        @Test
        @DisplayName("論理削除済みポリシー: GET・PATCH・再archive とも管理者にも不在と同一の404（是正前どおり）")
        void 論理削除済みは404() throws Exception {
            setAuth(adminAId);
            assertSameAsMissing(policyGet(policyArchivedAId), policyGet(MISSING_ID), 404, R001);
            assertSameAsMissing(policyPatch(policyArchivedAId), policyPatch(MISSING_ID), 404, R001);
            assertSameAsMissing(policyArchive(policyArchivedAId), policyArchive(MISSING_ID), 404, R001);
        }

        @Test
        @DisplayName("AC-10: ID 0・負数・Long.MAXは不在と同じ404、非数値は400")
        void ID境界() throws Exception {
            for (Long actor : List.of(adminAId, outsiderId)) {
                setAuth(actor);
                for (String id : BOUNDARY_IDS) {
                    expectError(get("/api/v1/cancellation-policies/" + id), 404, R001);
                    expectError(patchJson("/api/v1/cancellation-policies/" + id, policyPatchBody()), 404, R001);
                    expectError(post("/api/v1/cancellation-policies/" + id + "/archive"), 404, R001);
                }
                expectStatus(get("/api/v1/cancellation-policies/abc"), 400);
                expectStatus(patchJson("/api/v1/cancellation-policies/abc", policyPatchBody()), 400);
                expectStatus(post("/api/v1/cancellation-policies/abc/archive"), 400);
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // リクエスト
    // ═════════════════════════════════════════════════════════════════════

    private RequestBuilder waive(Long recordId) {
        return waiveRaw(String.valueOf(recordId));
    }

    private RequestBuilder waiveRaw(String recordId) {
        return postJson("/api/v1/recruitment-cancellation-records/" + recordId + "/waive",
                Map.of("reason", "W4 免除テスト"));
    }

    private RequestBuilder lift(String scopeType, Long scopeId, Long penaltyId) {
        return liftRaw(scopeType, String.valueOf(scopeId), String.valueOf(penaltyId));
    }

    private RequestBuilder liftRaw(String scopeType, String scopeId, String penaltyId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("liftReason", PenaltyLiftReason.ADMIN_MANUAL.name());
        body.put("liftNote", "W4 解除テスト");
        return postJson("/api/v1/scopes/" + scopeType + "/" + scopeId + "/penalties/" + penaltyId + "/lift", body);
    }

    private RequestBuilder confirm(Long listingId, Long participantId) {
        return confirmRaw(String.valueOf(listingId), String.valueOf(participantId));
    }

    private RequestBuilder confirmRaw(String listingId, String participantId) {
        return post("/api/v1/recruitment-listings/" + listingId + "/participants/" + participantId + "/confirm");
    }

    private RequestBuilder policyGet(Long id) {
        return get("/api/v1/cancellation-policies/" + id);
    }

    private RequestBuilder policyPatch(Long id) {
        return patchJson("/api/v1/cancellation-policies/" + id, policyPatchBody());
    }

    private RequestBuilder policyArchive(Long id) {
        return post("/api/v1/cancellation-policies/" + id + "/archive");
    }

    private Map<String, Object> policyPatchBody() {
        Map<String, Object> tier = new LinkedHashMap<>();
        tier.put("tierOrder", 1);
        tier.put("appliesAtOrBeforeHours", 12);
        tier.put("feeType", CancellationFeeType.FIXED.name());
        tier.put("feeValue", 500);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("policyName", "W4 編集後");
        body.put("tiers", List.of(tier));
        return body;
    }

    private RequestBuilder postJson(String url, Object body) {
        return post(url).contentType(MediaType.APPLICATION_JSON).content(toJson(body));
    }

    private RequestBuilder patchJson(String url, Object body) {
        return patch(url).contentType(MediaType.APPLICATION_JSON).content(toJson(body));
    }

    private String toJson(Object body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 検証ヘルパー
    // ═════════════════════════════════════════════════════════════════════

    /** 同じ利用者が叩いた実在 ID と不在 ID の応答（status・code・message）が完全一致し、期待の 404 であること。 */
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

    /** 1 行を全列（更新時刻・version を含む）そのまま文字列にする。 */
    private String row(String table, Long id) {
        em.flush();
        em.clear();
        @SuppressWarnings("unchecked")
        List<Object> rows = em.createNativeQuery("SELECT * FROM " + table + " WHERE id = :id")
                .setParameter("id", id).getResultList();
        return rows.stream().map(r -> r instanceof Object[] cols ? Arrays.deepToString(cols) : String.valueOf(r))
                .collect(Collectors.joining(","));
    }

    private String tiers(Long policyId) {
        em.flush();
        em.clear();
        @SuppressWarnings("unchecked")
        List<Object> rows = em.createNativeQuery(
                        "SELECT * FROM recruitment_cancellation_policy_tiers WHERE policy_id = :id ORDER BY id")
                .setParameter("id", policyId).getResultList();
        return rows.stream().map(r -> r instanceof Object[] cols ? Arrays.deepToString(cols) : String.valueOf(r))
                .collect(Collectors.joining(","));
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
                               Long createdBy) {
        LocalDateTime start = LocalDateTime.now().plusDays(30);
        return listingRepository.save(RecruitmentListingEntity.builder()
                .scopeType(scopeType)
                .scopeId(scopeId)
                .categoryId(categoryId)
                .title("W4MONEY 募集")
                .participationType(RecruitmentParticipationType.INDIVIDUAL)
                .startAt(start)
                .endAt(start.plusHours(2))
                .applicationDeadline(start.minusDays(1))
                .autoCancelAt(start.minusDays(2))
                .capacity(10)
                .minCapacity(1)
                .status(RecruitmentListingStatus.OPEN)
                .visibility(visibility)
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
                .build()).getId();
    }

    private Long insertSetting(RecruitmentScopeType scopeType, Long scopeId) {
        return penaltySettingRepository.save(RecruitmentPenaltySettingEntity.builder()
                .scopeType(scopeType).scopeId(scopeId).build()).getId();
    }

    private Long insertPenalty(Long userId, RecruitmentScopeType scopeType, Long scopeId, Long settingId,
                               boolean expired) {
        LocalDateTime now = LocalDateTime.now();
        return penaltyRepository.save(RecruitmentUserPenaltyEntity.builder()
                .userId(userId)
                .scopeType(scopeType)
                .scopeId(scopeId)
                .triggeredBySettingId(settingId)
                .triggeredNoShowCount(3)
                .startedAt(expired ? now.minusDays(40) : now.minusDays(1))
                .expiresAt(expired ? now.minusDays(1) : now.plusDays(30))
                .build()).getId();
    }

    private Long insertPolicy(Long teamId, boolean template) {
        Long id = policyRepository.save(RecruitmentCancellationPolicyEntity.builder()
                .scopeType(RecruitmentScopeType.TEAM)
                .scopeId(teamId)
                .policyName("W4MONEY ポリシー")
                .freeUntilHoursBefore(48)
                .isTemplatePolicy(template)
                .createdBy(adminAId)
                .build()).getId();
        tierRepository.save(RecruitmentCancellationPolicyTierEntity.builder()
                .policyId(id)
                .tierOrder(1)
                .appliesAtOrBeforeHours(24)
                .feeType(CancellationFeeType.PERCENTAGE)
                .feeValue(50)
                .build());
        return id;
    }

    /** 債務者の取消済み参加・キャンセル記録・escrow（受取先）を一組で作る。record.teamId は個人参加なので null。 */
    private Long insertRecordWithEscrow(Long listingId, Long debtor, CancellationPaymentStatus status,
                                        ScopeKind payeeKind, UUID payeeAccountId) {
        Long participantId = insertParticipant(listingId, debtor, RecruitmentParticipantStatus.CANCELLED);
        Long recordId = recordRepository.save(RecruitmentCancellationRecordEntity.builder()
                .participantId(participantId)
                .listingId(listingId)
                .userId(debtor)
                .teamId(null)
                .cancelledAt(LocalDateTime.now())
                .cancelledBy(debtor)
                .cancelSource(CancellationSource.USER)
                .hoursBeforeStart(6)
                .feeAmount(3_000)
                .paymentStatus(status)
                .build()).getId();
        escrowTransactionRepository.save(EscrowTransactionEntity.builder()
                .sourceKind(EscrowSourceKind.RECRUITMENT)
                .sourceId(listingId)
                .sourceParticipantId(participantId)
                .captureMode(EscrowCaptureMode.MANUAL)
                .payerScopeKind(ScopeKind.USER)
                .payerScopeId(debtor)
                .payeeKind(payeeKind)
                .payeeConnectAccountId(payeeAccountId)
                .faceAmount(10_000L)
                .amount(10_250L)
                .applicationFeeAmount(250L)
                .currency("JPY")
                .feePolicyKey("RECRUITMENT_DEFAULT")
                .status(EscrowStatus.AUTHORIZED)
                .build());
        return recordId;
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

    /**
     * {@code MANAGE_RECRUITMENTS} を権限カタログへ登録し ADMIN へ既定付与する（本番マイグレーションの写し）。
     * 試験基盤は ddl-auto で Flyway のシードが無いため、受取チームの精算管理者判定に必要（既存の免除 IT と同じ）。
     */
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
                                + "VALUES (:email, 'W4MONEY', 'テスト', 'W4MONEY テスト', 'ACTIVE', "
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
                                + "CONCAT('w4m-', LEFT(REPLACE(UUID(),'-',''),8)), NOW(), NOW())")
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
                                + "CONCAT('w4m-o-', LEFT(REPLACE(UUID(),'-',''),8)), NOW(), NOW())")
                .setParameter("name", name)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM organizations WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult()).longValue();
    }
}
