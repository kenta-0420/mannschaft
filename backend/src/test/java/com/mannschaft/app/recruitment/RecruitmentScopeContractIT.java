package com.mannschaft.app.recruitment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.recruitment.entity.RecruitmentListingEntity;
import com.mannschaft.app.recruitment.entity.RecruitmentParticipantEntity;
import com.mannschaft.app.recruitment.entity.RecruitmentSubcategoryEntity;
import com.mannschaft.app.recruitment.entity.RecruitmentTemplateEntity;
import com.mannschaft.app.recruitment.entity.RecruitmentUserPenaltyEntity;
import com.mannschaft.app.recruitment.repository.RecruitmentListingRepository;
import com.mannschaft.app.recruitment.repository.RecruitmentParticipantRepository;
import com.mannschaft.app.recruitment.repository.RecruitmentSubcategoryRepository;
import com.mannschaft.app.recruitment.repository.RecruitmentTemplateRepository;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 認可番人「裏目付」第二陣・部隊C（乙）— recruitment ドメイン 兄弟EP の認可回帰固定（AC-R7）。
 *
 * <p><b>位置づけ</b>: 本ファイルは<b>穴を塞ぐためのものではない</b>。NO_SHOW 異議解決の越境穴
 * （{@code RecruitmentNoShowScopeContractIT} が扱う）と同じ「スコープ帰属」の論点を持つ兄弟 EP 群が
 * <b>現状すでに安全であること</b>を実 HTTP + 実 MySQL 経路で固定し、将来のリファクタで
 * 帰属検証が落ちたら CI が落ちるようにするのが目的である。挙動は一切変更していない。</p>
 *
 * <p><b>recruitment ドメインの認可モデル</b>: 本ドメインの Controller には {@code @PreAuthorize} が
 * 1 つも存在せず、認可はすべて Service 内の {@code AccessControlService#checkAdminOrAbove} に依存する。
 * 帰属検証の型は次の 2 系統に分かれており、どちらも「越境を許さない」点では等価だが、
 * <b>返るステータス／エラーコードが異なる</b>ため、本テストはその差異ごと固定する。</p>
 *
 * <ol>
 *   <li><b>URL スコープ先行型</b>（{@code attend} / {@code archive} / {@code from-template}）:
 *       パス由来のスコープで {@code checkAdminOrAbove} → その後スコープ済みクエリ
 *       （{@code findByIdAndListingId} / {@code findByIdAndScopeTypeAndScopeId}）または明示的な
 *       スコープ突合（{@code TEMPLATE_SCOPE_MISMATCH}）で対象を絞る。越境 ID は
 *       {@code RECRUITMENT_*}（＝<b>400</b>）に畳み込まれる。</li>
 *   <li><b>エンティティ由来型</b>（{@code lift} / {@code confirm}）:
 *       まず対象レコードを引き、<b>そのレコード自身の scope</b> に対して {@code checkAdminOrAbove} する。
 *       URL の {@code scopeType}/{@code scopeId}・{@code listingId} は認可に使われない（＝飾り）。
 *       他テナントのレコード ID を差し込んでも、認可が被害者テナント側で評価されるため
 *       攻撃者は管理者ではなく <b>403</b>（{@code COMMON_002}）で弾かれる。</li>
 * </ol>
 *
 * <p><b>期待ステータスの根拠（AC-R8）</b>: {@code GlobalExceptionHandler.ERROR_CODE_STATUS_MAP} に
 * 登録された {@code RECRUITMENT_*} は {@code RECRUITMENT_204}/{@code RECRUITMENT_207} の 2 件のみ。
 * それ以外は {@code resolveHttpStatus} の Severity 既定にフォールバックし、
 * {@code Severity.WARN} → <b>400</b>。よって {@code LISTING_NOT_FOUND}（{@code RECRUITMENT_001}）も
 * {@code TEMPLATE_SCOPE_MISMATCH}（{@code RECRUITMENT_314}）も {@code PENALTY_NOT_FOUND}
 * （{@code RECRUITMENT_310}）も実際に返るのは 400 である（Javadoc の「404」表記は実態と異なる）。
 * 一方 {@code CommonErrorCode.COMMON_002} のみ 403 で明示登録されている。
 * {@code RECRUITMENT_*} のステータス整理は FE 分岐・既存 E2E への波及を避けるため別課題 #2468。</p>
 *
 * <p><b>観察事項（本 PR では挙動を変えない）</b>: エンティティ由来型の 2 EP は、
 * 「越境した実在 ID」が 403 / 「不在 ID」が 400 と<b>応答が分かれる</b>ため、ID の実在が
 * 応答差分から漏れる（実在オラクル）。URL スコープ先行型はいずれも 400 に収束しこの問題がない。
 * 統一の要否は #2468 の検討対象として最終報告に挙げる。</p>
 *
 * @see RecruitmentNoShowScopeContractIT NO_SHOW 異議解決の越境穴（根治対象）
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("recruitment 兄弟EP 認可回帰固定（裏目付C・AC-R7）")
class RecruitmentScopeContractIT extends AbstractMySqlIntegrationTest {

    /** 実在しないリソース ID（実在オラクルの対照）。 */
    private static final long ABSENT_ID = 999_999_999L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private RecruitmentListingRepository listingRepository;

    @Autowired
    private RecruitmentParticipantRepository participantRepository;

    @Autowired
    private RecruitmentSubcategoryRepository subcategoryRepository;

    @Autowired
    private RecruitmentTemplateRepository templateRepository;

    @Autowired
    private RecruitmentUserPenaltyRepository penaltyRepository;

    @PersistenceContext
    private EntityManager em;

    private Long teamAId;
    private Long teamBId;

    /** teamA の ADMIN（正当な管理者）。 */
    private Long adminAId;
    /** teamA の非管理者メンバー。 */
    private Long memberAId;
    /** teamB の ADMIN（越境攻撃者役）。 */
    private Long adminBId;
    /** どこにも所属しない完全な部外者。 */
    private Long outsiderId;

    private Long listingAId;
    private Long listingBId;

    /** listingA の CONFIRMED 参加者（出席チェックの正常系）。 */
    private Long participantAConfirmedId;
    /** listingB の CONFIRMED 参加者（出席チェックの越境 ID）。 */
    private Long participantBConfirmedId;
    /** listingA の APPLIED 参加者（申込確定の正常系）。 */
    private Long participantAAppliedId;
    /** listingB の APPLIED 参加者（申込確定の越境 ID）。 */
    private Long participantBAppliedId;

    private Long subcategoryAId;
    private Long subcategoryBId;
    private Long templateAId;
    private Long templateBId;
    private Long penaltyAId;
    private Long penaltyBId;

    @BeforeEach
    void setUp() {
        teamAId = insertTeam("RCRTSIB チームA");
        teamBId = insertTeam("RCRTSIB チームB");

        adminAId = insertUser("rcrtsib-admin-a@example.com");
        memberAId = insertUser("rcrtsib-member-a@example.com");
        adminBId = insertUser("rcrtsib-admin-b@example.com");
        outsiderId = insertUser("rcrtsib-outsider@example.com");

        // isScopeAdmin（user_roles）と isMember（memberships）は別系統のため ADMIN にも両方張る。
        MembershipTestHelper.insertMembership(em, adminAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminAId, "ADMIN", teamAId, null);
        MembershipTestHelper.insertMembership(em, memberAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        MembershipTestHelper.insertMembership(em, adminBId, ScopeType.TEAM, teamBId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminBId, "ADMIN", teamBId, null);

        listingAId = insertListing(teamAId, adminAId);
        listingBId = insertListing(teamBId, adminBId);

        participantAConfirmedId = insertParticipant(
                listingAId, memberAId, RecruitmentParticipantStatus.CONFIRMED);
        participantBConfirmedId = insertParticipant(
                listingBId, adminBId, RecruitmentParticipantStatus.CONFIRMED);
        participantAAppliedId = insertParticipant(
                listingAId, outsiderId, RecruitmentParticipantStatus.APPLIED);
        participantBAppliedId = insertParticipant(
                listingBId, outsiderId, RecruitmentParticipantStatus.APPLIED);

        subcategoryAId = insertSubcategory(teamAId, adminAId);
        subcategoryBId = insertSubcategory(teamBId, adminBId);
        templateAId = insertTemplate(teamAId, adminAId);
        templateBId = insertTemplate(teamBId, adminBId);
        penaltyAId = insertPenalty(memberAId, teamAId);
        penaltyBId = insertPenalty(adminBId, teamBId);

        em.flush();
        em.clear();
    }

    // ═════════════════════════════════════════════════════════════════════
    // 1. PATCH /recruitment-listings/{listingId}/participants/{participantId}/attend
    //    （出席チェック・URL スコープ先行型 + findByIdAndListingId のスコープ済みクエリ）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("1. PATCH /recruitment-listings/{listingId}/participants/{participantId}/attend（出席チェック）")
    class MarkAttended {

        /** AC-R7: 別募集の participantId を自募集の URL に差し込むと 400（{@code findByIdAndListingId} が空に畳み込む）。 */
        @Test
        @DisplayName("AC-R7: 正当ADMINが別募集のparticipantIdを差し込むと400（スコープ済みクエリで遮断）")
        void ac_r7_越境participantIdは400() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(patch("/api/v1/recruitment-listings/{listingId}/participants/{participantId}/attend",
                            listingAId, participantBConfirmedId))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code")
                            .value(RecruitmentErrorCode.LISTING_NOT_FOUND.getCode()));
        }

        /** AC-R7: 越境した実在 ID と不在 ID が同一応答（URL スコープ先行型はオラクルが立たない）。 */
        @Test
        @DisplayName("AC-R7: 越境の実在IDと不在IDが同一応答（実在オラクル封じ）")
        void ac_r7_越境IDと不在IDは同一応答() throws Exception {
            setAuth(adminAId);
            String crossTenant = mockMvc.perform(
                            patch("/api/v1/recruitment-listings/{listingId}/participants/{participantId}/attend",
                                    listingAId, participantBConfirmedId))
                    .andExpect(status().isBadRequest())
                    .andReturn().getResponse().getContentAsString();
            String absent = mockMvc.perform(
                            patch("/api/v1/recruitment-listings/{listingId}/participants/{participantId}/attend",
                                    listingAId, ABSENT_ID))
                    .andExpect(status().isBadRequest())
                    .andReturn().getResponse().getContentAsString();

            assertThat(crossTenant)
                    .as("越境した実在participantIdと不在IDの応答は完全一致でなければならない")
                    .isEqualTo(absent);
        }

        /** AC-R7: 遮断時に別募集の参加者ステータスが書き換わっていない。 */
        @Test
        @DisplayName("AC-R7: 遮断時に別募集の参加者がDB上で書き換わっていない")
        void ac_r7_遮断時に参加者は不変() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(patch("/api/v1/recruitment-listings/{listingId}/participants/{participantId}/attend",
                            listingAId, participantBConfirmedId))
                    .andExpect(status().isBadRequest());

            em.flush();
            em.clear();

            assertThat(participantRepository.findById(participantBConfirmedId).orElseThrow().getStatus())
                    .as("越境の出席チェックで teamB の参加者が ATTENDED にされてはならない")
                    .isEqualTo(RecruitmentParticipantStatus.CONFIRMED);
        }

        /** AC-R7: 非管理者メンバーは 403。 */
        @Test
        @DisplayName("AC-R7: 非管理者メンバーは403")
        void ac_r7_非管理者は403() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(patch("/api/v1/recruitment-listings/{listingId}/participants/{participantId}/attend",
                            listingAId, participantAConfirmedId))
                    .andExpect(status().isForbidden());
        }

        /** AC-R7: 部外者は 403。 */
        @Test
        @DisplayName("AC-R7: 部外者は403")
        void ac_r7_部外者は403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(patch("/api/v1/recruitment-listings/{listingId}/participants/{participantId}/attend",
                            listingAId, participantAConfirmedId))
                    .andExpect(status().isForbidden());
        }

        /** AC-R7: 正当 ADMIN の出席チェックは 200（非回帰）。 */
        @Test
        @DisplayName("AC-R7: 正当ADMINの出席チェックは200（非回帰）")
        void ac_r7_正当ADMINは200() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(patch("/api/v1/recruitment-listings/{listingId}/participants/{participantId}/attend",
                            listingAId, participantAConfirmedId))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 2. POST /recruitment-listings/{listingId}/participants/{participantId}/confirm
    //    （申込確定・エンティティ由来型）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("2. POST /recruitment-listings/{listingId}/participants/{participantId}/confirm（申込確定）")
    class ConfirmApplication {

        /**
         * AC-R7: 別募集の participantId を差し込んでも越境書き込みは成立しない。
         *
         * <p>本 EP は URL の {@code listingId} を Service に渡さず、participant から辿った listing の
         * scope に対して {@code checkAdminOrAbove} する<b>エンティティ由来型</b>。よって攻撃者は
         * 被害者テナント（teamB）の管理者性を問われ、403 で弾かれる。
         * 「URL と record の不一致を検知しない」構造ではあるが、認可が被害者側で評価されるため
         * テナント越境は成立しない。この性質を回帰として固定する。</p>
         */
        @Test
        @DisplayName("AC-R7: 正当ADMINが別募集のparticipantIdを差し込むと403（エンティティ由来認可）")
        void ac_r7_越境participantIdは403() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(post("/api/v1/recruitment-listings/{listingId}/participants/{participantId}/confirm",
                            listingAId, participantBAppliedId))
                    .andExpect(status().isForbidden());
        }

        /** AC-R7: 遮断時に別募集の参加者が CONFIRMED に書き換わっていない。 */
        @Test
        @DisplayName("AC-R7: 遮断時に別募集の参加者がDB上で書き換わっていない")
        void ac_r7_遮断時に参加者は不変() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(post("/api/v1/recruitment-listings/{listingId}/participants/{participantId}/confirm",
                            listingAId, participantBAppliedId))
                    .andExpect(status().isForbidden());

            em.flush();
            em.clear();

            assertThat(participantRepository.findById(participantBAppliedId).orElseThrow().getStatus())
                    .as("越境の申込確定で teamB の参加者が CONFIRMED にされてはならない")
                    .isEqualTo(RecruitmentParticipantStatus.APPLIED);
        }

        /** AC-R7: 非管理者メンバーは 403。 */
        @Test
        @DisplayName("AC-R7: 非管理者メンバーは403")
        void ac_r7_非管理者は403() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(post("/api/v1/recruitment-listings/{listingId}/participants/{participantId}/confirm",
                            listingAId, participantAAppliedId))
                    .andExpect(status().isForbidden());
        }

        /** AC-R7: 部外者は 403。 */
        @Test
        @DisplayName("AC-R7: 部外者は403")
        void ac_r7_部外者は403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(post("/api/v1/recruitment-listings/{listingId}/participants/{participantId}/confirm",
                            listingAId, participantAAppliedId))
                    .andExpect(status().isForbidden());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 3. POST /scopes/{scopeType}/{scopeId}/penalties/{penaltyId}/lift
    //    （ペナルティ手動解除・エンティティ由来型）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("3. POST /scopes/{scopeType}/{scopeId}/penalties/{penaltyId}/lift（ペナルティ解除）")
    class LiftPenalty {

        /**
         * AC-R7: 別スコープの penaltyId を自スコープ URL に差し込んでも越境解除は成立しない。
         *
         * <p>{@code RecruitmentPenaltyService#liftPenalty} は URL の {@code scopeType}/{@code scopeId} を
         * 使わず、ペナルティ自身の {@code scopeId}/{@code scopeType} に対して {@code checkAdminOrAbove}
         * するエンティティ由来型。よって越境は 403 で弾かれる。</p>
         */
        @Test
        @DisplayName("AC-R7: 正当ADMINが別スコープのpenaltyIdを差し込むと403（エンティティ由来認可）")
        void ac_r7_越境penaltyIdは403() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(post("/api/v1/scopes/{scopeType}/{scopeId}/penalties/{penaltyId}/lift",
                            "TEAM", teamAId, penaltyBId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(liftBody())))
                    .andExpect(status().isForbidden());
        }

        /** AC-R7: 遮断時に別スコープのペナルティが解除されていない。 */
        @Test
        @DisplayName("AC-R7: 遮断時に別スコープのペナルティがDB上で解除されていない")
        void ac_r7_遮断時にペナルティは不変() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(post("/api/v1/scopes/{scopeType}/{scopeId}/penalties/{penaltyId}/lift",
                            "TEAM", teamAId, penaltyBId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(liftBody())))
                    .andExpect(status().isForbidden());

            em.flush();
            em.clear();

            assertThat(penaltyRepository.findById(penaltyBId).orElseThrow().getLiftedAt())
                    .as("越境のペナルティ解除で teamB のペナルティが解除されてはならない")
                    .isNull();
        }

        /** AC-R7: 非管理者メンバーは 403。 */
        @Test
        @DisplayName("AC-R7: 非管理者メンバーは403")
        void ac_r7_非管理者は403() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(post("/api/v1/scopes/{scopeType}/{scopeId}/penalties/{penaltyId}/lift",
                            "TEAM", teamAId, penaltyAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(liftBody())))
                    .andExpect(status().isForbidden());
        }

        /** AC-R7: 部外者は 403。 */
        @Test
        @DisplayName("AC-R7: 部外者は403")
        void ac_r7_部外者は403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(post("/api/v1/scopes/{scopeType}/{scopeId}/penalties/{penaltyId}/lift",
                            "TEAM", teamAId, penaltyAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(liftBody())))
                    .andExpect(status().isForbidden());
        }

        /** AC-R7: 正当 ADMIN のペナルティ解除は 200（非回帰）。 */
        @Test
        @DisplayName("AC-R7: 正当ADMINのペナルティ解除は200（非回帰）")
        void ac_r7_正当ADMINは200() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(post("/api/v1/scopes/{scopeType}/{scopeId}/penalties/{penaltyId}/lift",
                            "TEAM", teamAId, penaltyAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(liftBody())))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 4. POST /teams/{teamId}/recruitment-subcategories/{subcategoryId}/archive
    //    （サブカテゴリ論理削除・URL スコープ先行型 + スコープ済みクエリ）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("4. POST /teams/{teamId}/recruitment-subcategories/{subcategoryId}/archive（サブカテゴリ削除）")
    class ArchiveSubcategory {

        /** AC-R7: 別チームの subcategoryId を自チーム URL に差し込むと 400（スコープ済みクエリで遮断）。 */
        @Test
        @DisplayName("AC-R7: 正当ADMINが別チームのsubcategoryIdを差し込むと400")
        void ac_r7_越境subcategoryIdは400() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/recruitment-subcategories/{subcategoryId}/archive",
                            teamAId, subcategoryBId))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code")
                            .value(RecruitmentErrorCode.LISTING_NOT_FOUND.getCode()));
        }

        /** AC-R7: 越境の実在 ID と不在 ID が同一応答（実在オラクル封じ）。 */
        @Test
        @DisplayName("AC-R7: 越境の実在IDと不在IDが同一応答（実在オラクル封じ）")
        void ac_r7_越境IDと不在IDは同一応答() throws Exception {
            setAuth(adminAId);
            String crossTenant = mockMvc.perform(
                            post("/api/v1/teams/{teamId}/recruitment-subcategories/{subcategoryId}/archive",
                                    teamAId, subcategoryBId))
                    .andExpect(status().isBadRequest())
                    .andReturn().getResponse().getContentAsString();
            String absent = mockMvc.perform(
                            post("/api/v1/teams/{teamId}/recruitment-subcategories/{subcategoryId}/archive",
                                    teamAId, ABSENT_ID))
                    .andExpect(status().isBadRequest())
                    .andReturn().getResponse().getContentAsString();

            assertThat(crossTenant)
                    .as("越境した実在subcategoryIdと不在IDの応答は完全一致でなければならない")
                    .isEqualTo(absent);
        }

        /** AC-R7: 遮断時に別チームのサブカテゴリが論理削除されていない。 */
        @Test
        @DisplayName("AC-R7: 遮断時に別チームのサブカテゴリがDB上で削除されていない")
        void ac_r7_遮断時にサブカテゴリは不変() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/recruitment-subcategories/{subcategoryId}/archive",
                            teamAId, subcategoryBId))
                    .andExpect(status().isBadRequest());

            em.flush();
            em.clear();

            assertThat(subcategoryRepository.findById(subcategoryBId))
                    .as("越境の archive で teamB のサブカテゴリが論理削除されてはならない")
                    .isPresent();
        }

        /** AC-R7: 非管理者メンバーは 403。 */
        @Test
        @DisplayName("AC-R7: 非管理者メンバーは403")
        void ac_r7_非管理者は403() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/recruitment-subcategories/{subcategoryId}/archive",
                            teamAId, subcategoryAId))
                    .andExpect(status().isForbidden());
        }

        /** AC-R7: 別チーム ADMIN は 403。 */
        @Test
        @DisplayName("AC-R7: 別チームADMINは403")
        void ac_r7_別チームADMINは403() throws Exception {
            setAuth(adminBId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/recruitment-subcategories/{subcategoryId}/archive",
                            teamAId, subcategoryAId))
                    .andExpect(status().isForbidden());
        }

        /** AC-R7: 正当 ADMIN の archive は 204（非回帰）。 */
        @Test
        @DisplayName("AC-R7: 正当ADMINのarchiveは204（非回帰）")
        void ac_r7_正当ADMINは204() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/recruitment-subcategories/{subcategoryId}/archive",
                            teamAId, subcategoryAId))
                    .andExpect(status().isNoContent());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 5. POST /teams/{teamId}/recruitment-listings/from-template
    //    （テンプレートから募集作成・URL スコープ先行型 + 明示的スコープ突合）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("5. POST /teams/{teamId}/recruitment-listings/from-template（テンプレートから作成）")
    class CreateFromTemplate {

        /**
         * AC-R7: 別チームの templateId を自チーム URL で使おうとすると 400
         * （{@code TEMPLATE_SCOPE_MISMATCH}）。本ドメインにおける帰属検証の<b>お手本</b>。
         */
        @Test
        @DisplayName("AC-R7: 正当ADMINが別チームのtemplateIdを使うと400（TEMPLATE_SCOPE_MISMATCH）")
        void ac_r7_越境templateIdは400() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/recruitment-listings/from-template", teamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(fromTemplateBody(templateBId))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code")
                            .value(RecruitmentErrorCode.TEMPLATE_SCOPE_MISMATCH.getCode()));
        }

        /** AC-R7: 非管理者メンバーは 403。 */
        @Test
        @DisplayName("AC-R7: 非管理者メンバーは403")
        void ac_r7_非管理者は403() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/recruitment-listings/from-template", teamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(fromTemplateBody(templateAId))))
                    .andExpect(status().isForbidden());
        }

        /** AC-R7: 別チーム ADMIN は 403。 */
        @Test
        @DisplayName("AC-R7: 別チームADMINは403")
        void ac_r7_別チームADMINは403() throws Exception {
            setAuth(adminBId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/recruitment-listings/from-template", teamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(fromTemplateBody(templateAId))))
                    .andExpect(status().isForbidden());
        }

        /** AC-R7: 正当 ADMIN が自チームのテンプレートから作成すると 201（非回帰）。 */
        @Test
        @DisplayName("AC-R7: 正当ADMINの自チームテンプレート利用は201（非回帰）")
        void ac_r7_正当ADMINは201() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/recruitment-listings/from-template", teamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(fromTemplateBody(templateAId))))
                    .andExpect(status().isCreated());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 6. GET /teams/{teamId}/recruitment-subcategories（現挙動の明文化・仕様確認待ち）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("6. GET /teams/{teamId}/recruitment-subcategories（現挙動の明文化）")
    class ListSubcategories {

        /**
         * <b>観察事項（挙動は変更しない）</b>: 本 EP は {@code RecruitmentSubcategoryService#listByScope}
         * に {@code userId} すら渡しておらず、認可判定が存在しない。認証済みであれば誰でも任意チームの
         * サブカテゴリを列挙できる。機微度は低いものの「設計上パブリックでよい」という意図の有無が
         * 確認できていないため、本 PR では<b>現挙動をそのまま明文化</b>するに留める。
         * 仕様確認のうえ認可が必要と判断された場合、本テストは期待値ごと差し替えること。
         */
        @Test
        @DisplayName("観察: 部外者でもサブカテゴリ一覧は200（認可判定なし・仕様確認待ち）")
        void 観察_部外者でも200_認可なし() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/recruitment-subcategories", teamAId))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // ヘルパー
    // ═════════════════════════════════════════════════════════════════════

    private Map<String, Object> liftBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("liftReason", PenaltyLiftReason.ADMIN_MANUAL.name());
        body.put("liftNote", "裏目付テスト");
        return body;
    }

    private Map<String, Object> fromTemplateBody(Long templateId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("templateId", templateId);
        body.put("startAt", LocalDateTime.now().plusDays(30).toString());
        return body;
    }

    private void setAuth(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
    }

    private Long insertListing(Long teamId, Long createdBy) {
        LocalDateTime start = LocalDateTime.now().plusDays(30);
        return listingRepository.save(RecruitmentListingEntity.builder()
                .scopeType(RecruitmentScopeType.TEAM)
                .scopeId(teamId)
                .categoryId(1L)
                .title("RCRTSIB 募集")
                .participationType(RecruitmentParticipationType.INDIVIDUAL)
                .startAt(start)
                .endAt(start.plusHours(2))
                .applicationDeadline(start.minusDays(1))
                .autoCancelAt(start.minusDays(2))
                .capacity(10)
                .minCapacity(1)
                .status(RecruitmentListingStatus.OPEN)
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

    private Long insertSubcategory(Long teamId, Long createdBy) {
        return subcategoryRepository.save(RecruitmentSubcategoryEntity.builder()
                .categoryId(1L)
                .scopeType(RecruitmentScopeType.TEAM)
                .scopeId(teamId)
                .name("RCRTSIB サブカテゴリ")
                .displayOrder(0)
                .createdBy(createdBy)
                .build()).getId();
    }

    private Long insertTemplate(Long teamId, Long createdBy) {
        return templateRepository.save(RecruitmentTemplateEntity.builder()
                .scopeType(RecruitmentScopeType.TEAM)
                .scopeId(teamId)
                .categoryId(1L)
                .templateName("RCRTSIB テンプレート")
                .title("RCRTSIB テンプレート募集")
                .createdBy(createdBy)
                .build()).getId();
    }

    private Long insertPenalty(Long userId, Long teamId) {
        return penaltyRepository.save(RecruitmentUserPenaltyEntity.builder()
                .userId(userId)
                .scopeType(RecruitmentScopeType.TEAM)
                .scopeId(teamId)
                .triggeredBySettingId(1L)
                .triggeredNoShowCount(3)
                .startedAt(LocalDateTime.now().minusDays(1))
                .expiresAt(LocalDateTime.now().plusDays(30))
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
                                + "VALUES (:email, 'RCRTSIB', 'テスト', 'RCRTSIB テスト', 'ACTIVE', "
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
                                + "CONCAT('rcrts-', LEFT(REPLACE(UUID(),'-',''),8)), NOW(), NOW())")
                .setParameter("name", name)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM teams WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult()).longValue();
    }
}
