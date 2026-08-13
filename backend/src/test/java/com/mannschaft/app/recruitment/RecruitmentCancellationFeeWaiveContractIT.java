package com.mannschaft.app.recruitment;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import com.mannschaft.app.recruitment.repository.RecruitmentCancellationRecordRepository;
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
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F03.11.1 キャンセル料の免除 EP の認可契約テスト（設計書 §10.2 / §10.3・AC-18/19/20/27/28/29）。
 *
 * <p><b>この EP が守るべきこと</b>: 免除は金銭債権を消す不可逆な操作である。免除できるのは
 * 「その金を受け取る側」と運営管理者だけであり、<b>受取先の判定は escrow の payee に基づかせる</b>。
 * 募集を作った者と謝礼の受取先は一致するとは限らない（{@code listing.payeeKind} / {@code payeeUserId} で
 * 別に指定できる）ため、作成者で判定すると免除できる範囲が受取先と食い違う。</p>
 *
 * <p><b>とりわけ債務者本人</b>: キャンセル料を負っている当人は、受取先でも運営でもない。
 * 債務者が自分の債務を消せてはならないため、本人の免除要求は 403 でなければならない。</p>
 *
 * <p>肯定側と否定側を対で起こす。肯定側だけを書くと「判定が常に true」でも緑になってしまう（§11.1）。</p>
 *
 * <p>金型: {@code RecruitmentNoShowScopeContractIT}
 * （{@code @AutoConfigureMockMvc(addFilters=false)} + 実 MySQL Testcontainers + 手動 SecurityContext）。
 * {@code addFilters=false} でもサービス層の認可は作動するため、認可の契約はここで検証できる。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("F03.11.1 キャンセル料免除 認可契約テスト（受取先判定・債務者遮断）")
class RecruitmentCancellationFeeWaiveContractIT extends AbstractMySqlIntegrationTest {

    /** 実在しないキャンセル記録 ID（実在オラクル封じの対照）。 */
    private static final long ABSENT_RECORD_ID = 999_999_999L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private RecruitmentCancellationRecordRepository cancellationRecordRepository;

    @Autowired
    private EscrowTransactionRepository escrowTransactionRepository;

    @Autowired
    private ConnectAccountRepository connectAccountRepository;

    /**
     * 徴収の非同期リスナは本 IT の対象外（免除の認可だけを見る）。
     * Stripe へ到達させないため、徴収リスナが依存する Stripe 提供者はモックに差し替える。
     */
    @MockitoBean
    private com.mannschaft.app.payment.stripe.StripePaymentProvider stripePaymentProvider;

    @PersistenceContext
    private EntityManager em;

    private Long teamAId;
    private Long teamBId;

    /** teamA（＝受取先）の ADMIN。免除できる。 */
    private Long payeeAdminAId;
    /** teamB の ADMIN。teamA 受取の記録には無関係＝免除できない。 */
    private Long otherAdminBId;
    /** キャンセル料を負っている本人。免除できない。 */
    private Long debtorId;
    /** どこにも所属しない部外者。免除できない。 */
    private Long outsiderId;
    /** 受取先が個人（payeeKind=USER）の記録における受取本人。免除できる。 */
    private Long individualPayeeId;

    /** teamA 受取・未払い（PENDING）のキャンセル記録。 */
    private Long teamPayeeRecordId;
    /** 個人受取・未払い（PENDING）のキャンセル記録。 */
    private Long userPayeeRecordId;
    /** teamA 受取・徴収済み（PAID）のキャンセル記録。 */
    private Long paidRecordId;

    @BeforeEach
    void setUp() {
        teamAId = insertTeam("CANFEE チームA");
        teamBId = insertTeam("CANFEE チームB");

        payeeAdminAId = insertUser("canfee-payee-admin-a@example.com");
        otherAdminBId = insertUser("canfee-other-admin-b@example.com");
        debtorId = insertUser("canfee-debtor@example.com");
        outsiderId = insertUser("canfee-outsider@example.com");
        individualPayeeId = insertUser("canfee-individual-payee@example.com");

        // isScopeAdmin（user_roles）と isMember（memberships）は別系統のため、ADMIN にも memberships 行を張る。
        MembershipTestHelper.insertMembership(em, payeeAdminAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, payeeAdminAId, "ADMIN", teamAId, null);
        MembershipTestHelper.insertMembership(em, otherAdminBId, ScopeType.TEAM, teamBId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, otherAdminBId, "ADMIN", teamBId, null);
        MembershipTestHelper.insertMembership(em, debtorId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);

        grantManageRecruitmentsToAdmin();

        UUID teamAccountId = insertConnectAccount(ScopeKind.TEAM, teamAId);
        UUID userAccountId = insertConnectAccount(ScopeKind.USER, individualPayeeId);

        // 記録 ↔ escrow は三つ組（sourceKind, listingId, participantId）で結ばれる。
        teamPayeeRecordId = insertRecord(1001L, 2001L, debtorId, CancellationPaymentStatus.PENDING);
        insertEscrow(1001L, 2001L, ScopeKind.TEAM, teamAccountId);

        userPayeeRecordId = insertRecord(1002L, 2002L, debtorId, CancellationPaymentStatus.PENDING);
        insertEscrow(1002L, 2002L, ScopeKind.USER, userAccountId);

        paidRecordId = insertRecord(1003L, 2003L, debtorId, CancellationPaymentStatus.PAID);
        insertEscrow(1003L, 2003L, ScopeKind.TEAM, teamAccountId);

        em.flush();
        em.clear();
    }

    // ═════════════════════════════════════════════════════════════════════
    // 1. 受取先が TEAM（AC-27）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("1. 受取先が TEAM のとき")
    class TeamPayee {

        /** AC-27(肯定): 受取先チームの支払い管理権限者は免除できる。 */
        @Test
        @DisplayName("AC-27(肯定): 受取先 TEAM の ADMIN は免除でき、記録が WAIVED になる")
        void ac27_受取先TEAMのADMINは免除できる() throws Exception {
            setAuth(payeeAdminAId);
            waive(teamPayeeRecordId, "主催者都合のため免除").andExpect(status().isOk());

            em.flush();
            em.clear();

            RecruitmentCancellationRecordEntity waived =
                    cancellationRecordRepository.findById(teamPayeeRecordId).orElseThrow();
            assertThat(waived.getPaymentStatus()).isEqualTo(CancellationPaymentStatus.WAIVED);
            assertThat(waived.getNotes()).isEqualTo("主催者都合のため免除");
        }

        /** AC-27(否定)/AC-20: 無関係な TEAM の ADMIN は免除できない（テナント越境の遮断）。 */
        @Test
        @DisplayName("AC-27(否定): 無関係な TEAM の ADMIN は 403 で、記録は書き換わらない")
        void ac27_無関係TEAMのADMINは拒否される() throws Exception {
            setAuth(otherAdminBId);
            waive(teamPayeeRecordId, "他団体の債権を消したい")
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code")
                            .value(com.mannschaft.app.common.CommonErrorCode.COMMON_002.getCode()));

            assertUnchanged(teamPayeeRecordId, CancellationPaymentStatus.PENDING);
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 2. 受取先が個人（AC-28）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("2. 受取先が個人（payeeKind=USER）のとき")
    class UserPayee {

        /**
         * AC-28(肯定): 受取本人は免除できる。
         *
         * <p>既存 {@code authorizePayeeAdmin} は個人受領を対象外にしている（404 秘匿）ため、
         * 流用では本ケースが通らない。本 EP のために新たに定義した判定が効いていることを見る。</p>
         */
        @Test
        @DisplayName("AC-28(肯定): 受取先が個人のとき、その本人は免除できる")
        void ac28_個人受取の本人は免除できる() throws Exception {
            setAuth(individualPayeeId);
            waive(userPayeeRecordId, "個人受取として免除").andExpect(status().isOk());

            em.flush();
            em.clear();

            assertThat(cancellationRecordRepository.findById(userPayeeRecordId).orElseThrow().getPaymentStatus())
                    .isEqualTo(CancellationPaymentStatus.WAIVED);
        }

        /** AC-28(否定): 受取先が個人のとき、他人は免除できない。 */
        @Test
        @DisplayName("AC-28(否定): 受取先が個人のとき、他人は 403")
        void ac28_個人受取の他人は拒否される() throws Exception {
            setAuth(otherAdminBId);
            waive(userPayeeRecordId, "他人の債権を消したい").andExpect(status().isForbidden());

            assertUnchanged(userPayeeRecordId, CancellationPaymentStatus.PENDING);
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 3. 債務者・部外者・存在しない記録（AC-18 / AC-19 / AC-20）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("3. 免除できない主体")
    class Forbidden {

        /**
         * AC-18: キャンセル料を負っている本人は自分の記録を免除できない。
         *
         * <p>これを許すと債務者が自分の債務を消せてしまい、未払いによる申込ブロックが
         * 「自分でボタンを押すだけ」で外れる。本テストが落ちるということは、そのまま
         * キャンセル料が誰からも取れなくなることを意味する。</p>
         */
        @Test
        @DisplayName("AC-18: キャンセル料を負っている本人は自分の記録を免除できない（403）")
        void ac18_債務者は自分の債務を消せない() throws Exception {
            setAuth(debtorId);
            waive(teamPayeeRecordId, "自分で消したい").andExpect(status().isForbidden());

            assertUnchanged(teamPayeeRecordId, CancellationPaymentStatus.PENDING);
        }

        /** AC-19: 何の権限も持たない一般ユーザーは免除できない（IDOR）。 */
        @Test
        @DisplayName("AC-19: 何の権限も持たない一般ユーザーは 403")
        void ac19_部外者は拒否される() throws Exception {
            setAuth(outsiderId);
            waive(teamPayeeRecordId, "無関係だが消したい").andExpect(status().isForbidden());

            assertUnchanged(teamPayeeRecordId, CancellationPaymentStatus.PENDING);
        }

        /** AC-20: 存在しない記録 ID は 404（存在を推測させない）。 */
        @Test
        @DisplayName("AC-20: 存在しない記録 ID は 404")
        void ac20_存在しない記録は404() throws Exception {
            setAuth(payeeAdminAId);
            waive(ABSENT_RECORD_ID, "存在しない記録を免除").andExpect(status().isNotFound());
        }

        /** 理由は必須である（免除は不可逆であり、理由なき実行を許さない）。 */
        @Test
        @DisplayName("AC-10: 理由が空なら 400 で、記録は書き換わらない")
        void ac10_理由は必須() throws Exception {
            setAuth(payeeAdminAId);
            waive(teamPayeeRecordId, "   ").andExpect(status().isBadRequest());

            assertUnchanged(teamPayeeRecordId, CancellationPaymentStatus.PENDING);
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 4. 状態による分岐（AC-29）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("4. 対象状態")
    class TargetStatus {

        /** AC-29: 徴収済み（PAID）への免除は 409（免除ではなく返金の話であり混同させない）。 */
        @Test
        @DisplayName("AC-29: PAID の記録への免除は 409")
        void ac29_徴収済みへの免除は409() throws Exception {
            setAuth(payeeAdminAId);
            waive(paidRecordId, "徴収済みだが免除したい")
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code")
                            .value(RecruitmentErrorCode.CANCELLATION_FEE_ALREADY_PAID.getCode()));

            assertUnchanged(paidRecordId, CancellationPaymentStatus.PAID);
        }

        /**
         * AC-29(対): WAIVED への再免除は冪等に 200。
         *
         * <p>PAID の 409 と対で起こすことで、「終端状態なら何でも 409」にしていないことを示す。</p>
         */
        @Test
        @DisplayName("AC-29(対): WAIVED への再免除は冪等に 200")
        void ac29_再免除は冪等に成功する() throws Exception {
            setAuth(payeeAdminAId);
            waive(teamPayeeRecordId, "1 回目").andExpect(status().isOk());

            em.flush();
            em.clear();

            setAuth(payeeAdminAId);
            waive(teamPayeeRecordId, "2 回目").andExpect(status().isOk());

            em.flush();
            em.clear();

            RecruitmentCancellationRecordEntity record =
                    cancellationRecordRepository.findById(teamPayeeRecordId).orElseThrow();
            assertThat(record.getPaymentStatus()).isEqualTo(CancellationPaymentStatus.WAIVED);
            assertThat(record.getNotes())
                    .as("再免除は no-op であり、1 回目の理由を上書きしない")
                    .isEqualTo("1 回目");
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // ヘルパー
    // ═════════════════════════════════════════════════════════════════════

    private org.springframework.test.web.servlet.ResultActions waive(Long recordId, String reason) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("reason", reason);
        return mockMvc.perform(post("/api/v1/recruitment-cancellation-records/{recordId}/waive", recordId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    /** 遮断されたとき記録が DB 上で書き換わっていないことを照合する（ステータスだけでは不十分）。 */
    private void assertUnchanged(Long recordId, CancellationPaymentStatus expected) {
        em.flush();
        em.clear();
        assertThat(cancellationRecordRepository.findById(recordId).orElseThrow().getPaymentStatus())
                .as("遮断された免除でキャンセル記録が書き換えられてはならない")
                .isEqualTo(expected);
    }

    /**
     * {@code MANAGE_RECRUITMENTS} を権限カタログへ登録し ADMIN へ自動付与する（本番マイグレーションの写し）。
     *
     * <p><b>なぜ手で詰める必要があるか</b>: 本 IT の基底 {@code AbstractMySqlIntegrationTest} は
     * {@code spring.flyway.enabled=false} / {@code ddl-auto=create} で動く。スキーマは Entity から
     * 生成されるため {@code permissions} / {@code role_permissions} は<b>空の表</b>として作られ、
     * {@code V183…__add_manage_recruitments_permission.sql} のシードは適用されない。
     * よってフィクスチャ側で同じ行を作らないと、TEAM 受取の権限判定は誰に対しても成立しない。</p>
     *
     * <p>ここで作る行は<b>本番マイグレーションが作る行と同一</b>である（カタログ 1 行＋ADMIN への
     * {@code is_default=1} 付与のみ）。本番で成立しえない状態を捏造してはいない——それをやると
     * 死んだ機能が永久に緑になる。</p>
     *
     * <p><b>役割分担</b>: 「マイグレーションが権限を登録し忘れていないか」を守るのは実 Flyway を適用する
     * {@code ManageRecruitmentsPermissionFlywayIT} の責務である。本 IT が守るのは
     * 「権限が在るという前提のもとで、免除 EP の認可が受取先ごとに正しく効くか」である。</p>
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

    private void setAuth(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
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
                                + "VALUES (:email, 'CANFEE', 'テスト', 'CANFEE テスト', 'ACTIVE', "
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
                                + "CONCAT('canfee-', LEFT(REPLACE(UUID(),'-',''),8)), NOW(), NOW())")
                .setParameter("name", name)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM teams WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult()).longValue();
    }
}
