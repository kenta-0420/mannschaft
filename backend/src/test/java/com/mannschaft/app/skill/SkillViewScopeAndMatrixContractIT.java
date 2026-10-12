package com.mannschaft.app.skill;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.common.storage.PresignedUploadResult;
import com.mannschaft.app.common.storage.R2StorageService;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.skill.entity.MemberSkillEntity;
import com.mannschaft.app.skill.entity.SkillCategoryEntity;
import com.mannschaft.app.skill.repository.MemberSkillRepository;
import com.mannschaft.app.skill.repository.SkillCategoryRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.MembershipTestHelper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

/**
 * CMP-261012-0313 PR-A（BE）: 資格の閲覧範囲・スキルマトリクス・検索の証明書有無の契約（試練 / red 先行）。
 *
 * <p>正本: {@code docs/features/F07.5_skill_certification.md} §2（対象ロール）。マスター裁可（2026-10-12）
 * 「閲覧範囲は設計書どおりに絞る」。</p>
 * <ul>
 *   <li>B-AC1: search / skill-matrix / export は ADMIN・SYSTEM_ADMIN・{@code MANAGE_MEMBER} を持つ DEPUTY_ADMIN だけ。
 *       それ以外は 403 COMMON_002（一覧型 §3.3.1。teamId の実在によらず同一応答）。</li>
 *   <li>B-AC2: me / 登録 / upload-url / skill-categories は MEMBER 以上（ADMIN・DEPUTY_ADMIN・MEMBER）。
 *       SUPPORTER・GUEST・非メンバーは 403 COMMON_002。個別 ID 系（CMP-261010-2226）の契約は変えない。</li>
 *   <li>B-AC3〜B-AC6: マトリクスの行・表示名・確認待ち・未登録セル形。</li>
 *   <li>B-AC7: 検索結果の hasCertificate。</li>
 * </ul>
 *
 * <p>金型: {@code MemberSkillAccessConcealmentContractIT}（{@code addFilters=false} + 実 MySQL + 手動 SecurityContext）。
 * {@code @MockitoBean} の集合を揃えてコンテキストを共有する。</p>
 *
 * <p>{@code MANAGE_MEMBER} のカタログ行は、test profile が Flyway 無効で {@code permissions} が空表のため、
 * <b>登録マイグレーションの本文をそのまま実行して</b>作る（{@code AdminOrPermissionInScopeIT} と同じ方針。
 * テスト内で行を捏造すると本番で成立しえない土台の上で緑になる）。カタログを要するテストだけが呼ぶ。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("CMP-261012-0313 資格の閲覧範囲・マトリクス・証明書有無の契約")
class SkillViewScopeAndMatrixContractIT extends AbstractMySqlIntegrationTest {

    private static final String MANAGE_MEMBER = "MANAGE_MEMBER";
    private static final String MIGRATION_RESOURCE =
            "db/migration/V239.20261012040500__add_manage_member_permission.sql";
    private static final long MISSING_TEAM_ID = 999_999_999L;
    private static final String UNKNOWN_USER = "不明なユーザー";

    /** 権限不足の応答（{@code CommonErrorCode.COMMON_002}）。 */
    private static final Resp FORBIDDEN = new Resp(403, "COMMON_002", "この操作を行う権限がありません");
    /** 資格不在の応答（{@code SkillErrorCode.SKILL_002}）。 */
    private static final Resp SKILL_NOT_FOUND = new Resp(404, "SKILL_002", "資格が見つかりません");

    /** B-AC1 の対象（管理者向けの一覧系）。 */
    private static final List<String> MANAGER_ENDPOINTS = List.of(
            "GET /skills/search", "GET /skill-matrix", "GET /skills/export");
    /** B-AC2 の対象（MEMBER 以上の自分の資格系）。 */
    private static final List<String> MEMBER_ENDPOINTS = List.of(
            "GET /skills/me", "POST /skills", "POST /skills/upload-url", "GET /skill-categories");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MemberSkillRepository memberSkillRepository;

    @Autowired
    private SkillCategoryRepository skillCategoryRepository;

    @MockitoBean
    private R2StorageService storageService;

    @PersistenceContext
    private EntityManager em;

    private Long teamAId;
    private Long teamBId;

    private Long adminId;             // teamA ADMIN（在籍 MEMBER + user_roles ADMIN）
    private Long deputyWithPermId;    // teamA DEPUTY_ADMIN（MANAGE_MEMBER を権限グループで付与。付与はテスト側で行う）
    private Long deputyWithoutPermId; // teamA DEPUTY_ADMIN（MANAGE_MEMBER なし）
    private Long memberId;            // teamA MEMBER
    private Long supporterId;         // teamA SUPPORTER
    private Long guestId;             // teamA GUEST（user_roles のみ・在籍なし）
    private Long outsiderId;          // teamB の MEMBER（teamA には無所属）
    private Long sysAdminId;          // SYSTEM_ADMIN（どこにも所属しない）
    private Long departedId;          // teamA を脱退済みの元 MEMBER

    private Long categoryXId;         // teamA の有効カテゴリ X
    private Long categoryYId;         // teamA の有効カテゴリ Y
    private int seq;

    @BeforeEach
    void setUp() {
        teamAId = insertTeam("SKVIEW チームA");
        teamBId = insertTeam("SKVIEW チームB");

        adminId = insertUser("skview-admin", "SKVIEW 管理者");
        deputyWithPermId = insertUser("skview-deputy-with", "SKVIEW 副管理者あり");
        deputyWithoutPermId = insertUser("skview-deputy-without", "SKVIEW 副管理者なし");
        memberId = insertUser("skview-member", "SKVIEW 一般");
        supporterId = insertUser("skview-supporter", "SKVIEW サポーター");
        guestId = insertUser("skview-guest", "SKVIEW ゲスト");
        outsiderId = insertUser("skview-outsider", "SKVIEW 部外者");
        sysAdminId = insertUser("skview-sysadmin", "SKVIEW 運営");
        departedId = insertUser("skview-departed", "SKVIEW 脱退者");

        MembershipTestHelper.insertMembership(em, adminId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminId, "ADMIN", teamAId, null);
        MembershipTestHelper.insertMembership(em, deputyWithPermId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, deputyWithPermId, "DEPUTY_ADMIN", teamAId, null);
        MembershipTestHelper.insertMembership(em, deputyWithoutPermId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, deputyWithoutPermId, "DEPUTY_ADMIN", teamAId, null);
        MembershipTestHelper.insertMembership(em, memberId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        MembershipTestHelper.insertMembership(em, supporterId, ScopeType.TEAM, teamAId, RoleKind.SUPPORTER);
        MembershipTestHelper.insertUserRole(em, guestId, "GUEST", teamAId, null);
        MembershipTestHelper.insertMembership(em, outsiderId, ScopeType.TEAM, teamBId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, sysAdminId, "SYSTEM_ADMIN", null, null);
        MembershipTestHelper.insertMembership(em, departedId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        em.createNativeQuery("UPDATE memberships SET left_at = NOW(), updated_at = NOW() "
                        + "WHERE user_id = :uid AND scope_type = 'TEAM' AND scope_id = :sid")
                .setParameter("uid", departedId)
                .setParameter("sid", teamAId)
                .executeUpdate();

        categoryXId = saveCategory(teamAId, "SKVIEW カテゴリX", 1);
        categoryYId = saveCategory(teamAId, "SKVIEW カテゴリY", 2);

        em.flush();
        em.clear();

        given(storageService.generateUploadUrl(anyString(), anyString(), any(Duration.class)))
                .willReturn(new PresignedUploadResult("https://storage.example.invalid/upload", "k", 900L));
    }

    // ═════════════════════════════════════════════════════════════════════
    // B-AC1: search / skill-matrix / export の閲覧範囲（ロールごとに 1 テスト）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("B-AC1: search・skill-matrix・export は ADMIN・SYSTEM_ADMIN・MANAGE_MEMBER を持つ DEPUTY_ADMIN だけ")
    class ManagerEndpoints {

        @Test
        @DisplayName("B-AC1: ADMIN は search 200・skill-matrix 200・export 202")
        void BAC1_ADMINは許可() throws Exception {
            assertManagerAllowed(adminId);
        }

        @Test
        @DisplayName("B-AC1: SYSTEM_ADMIN（非所属）は search 200・skill-matrix 200・export 202")
        void BAC1_SYSTEM_ADMINは許可() throws Exception {
            assertManagerAllowed(sysAdminId);
        }

        @Test
        @DisplayName("B-AC1: MANAGE_MEMBER を持つ DEPUTY_ADMIN は search 200・skill-matrix 200・export 202")
        void BAC1_MANAGE_MEMBERありのDEPUTY_ADMINは許可() throws Exception {
            seedManageMemberCatalogFromMigration();
            grantManageMemberToDeputy(deputyWithPermId);
            assertManagerAllowed(deputyWithPermId);
        }

        @Test
        @DisplayName("B-AC1: MANAGE_MEMBER を持たない DEPUTY_ADMIN は 403 COMMON_002（不在チームと同一応答）")
        void BAC1_MANAGE_MEMBERなしのDEPUTY_ADMINは403() throws Exception {
            assertManagerDenied(deputyWithoutPermId);
        }

        @Test
        @DisplayName("B-AC1: MEMBER は 403 COMMON_002（不在チームと同一応答）")
        void BAC1_MEMBERは403() throws Exception {
            assertManagerDenied(memberId);
        }

        @Test
        @DisplayName("B-AC1: SUPPORTER は 403 COMMON_002（不在チームと同一応答）")
        void BAC1_SUPPORTERは403() throws Exception {
            assertManagerDenied(supporterId);
        }

        @Test
        @DisplayName("B-AC1: GUEST は 403 COMMON_002（不在チームと同一応答）")
        void BAC1_GUESTは403() throws Exception {
            assertManagerDenied(guestId);
        }

        @Test
        @DisplayName("B-AC1: 非メンバー（他チーム所属）は 403 COMMON_002（不在チームと同一応答）")
        void BAC1_非メンバーは403() throws Exception {
            assertManagerDenied(outsiderId);
        }

        private void assertManagerAllowed(Long actor) throws Exception {
            for (String endpoint : MANAGER_ENDPOINTS) {
                int expected = endpoint.endsWith("/export") ? 202 : 200;
                assertThat(callEndpoint(actor, endpoint, teamAId).status())
                        .as("actor=%d endpoint=%s", actor, endpoint)
                        .isEqualTo(expected);
            }
        }

        private void assertManagerDenied(Long actor) throws Exception {
            for (String endpoint : MANAGER_ENDPOINTS) {
                Resp existing = callEndpoint(actor, endpoint, teamAId);
                Resp missing = callEndpoint(actor, endpoint, MISSING_TEAM_ID);
                assertThat(existing).as("actor=%d endpoint=%s（実在チーム）", actor, endpoint).isEqualTo(FORBIDDEN);
                assertThat(missing).as("actor=%d endpoint=%s（不在チーム）", actor, endpoint).isEqualTo(existing);
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // B-AC2: me / 登録 / upload-url / skill-categories は MEMBER 以上
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("B-AC2: me・登録・upload-url・skill-categories は MEMBER 以上（SUPPORTER・GUEST・非メンバーは 403）")
    class MemberEndpoints {

        @Test
        @DisplayName("B-AC2: ADMIN は me 200・登録 201・upload-url 200・skill-categories 200")
        void BAC2_ADMINは許可() throws Exception {
            assertMemberAllowed(adminId);
        }

        @Test
        @DisplayName("B-AC2: DEPUTY_ADMIN（MANAGE_MEMBER なし）は me 200・登録 201・upload-url 200・skill-categories 200")
        void BAC2_DEPUTY_ADMINは許可() throws Exception {
            assertMemberAllowed(deputyWithoutPermId);
        }

        @Test
        @DisplayName("B-AC2: MEMBER は me 200・登録 201・upload-url 200・skill-categories 200")
        void BAC2_MEMBERは許可() throws Exception {
            assertMemberAllowed(memberId);
        }

        @Test
        @DisplayName("B-AC2: SUPPORTER は 403 COMMON_002、資格は登録されない")
        void BAC2_SUPPORTERは403() throws Exception {
            assertMemberDenied(supporterId);
        }

        @Test
        @DisplayName("B-AC2: GUEST は 403 COMMON_002、資格は登録されない")
        void BAC2_GUESTは403() throws Exception {
            assertMemberDenied(guestId);
        }

        @Test
        @DisplayName("B-AC2: 非メンバー（他チーム所属）は 403 COMMON_002、資格は登録されない")
        void BAC2_非メンバーは403() throws Exception {
            assertMemberDenied(outsiderId);
        }

        @Test
        @DisplayName("B-AC2: SYSTEM_ADMIN（非所属）は従来どおり 403 COMMON_002（自分の資格系は所属者のもの）")
        void BAC2_非所属のSYSTEM_ADMINは従来どおり403() throws Exception {
            assertMemberDenied(sysAdminId);
        }

        @Test
        @DisplayName("B-AC2: 個別ID系は不変 — SUPPORTER が他人の資格を叩くと 403 COMMON_002（所属者扱い）、不在IDは 404 SKILL_002")
        void BAC2_個別ID系のSUPPORTER応答は不変() throws Exception {
            Long skillId = saveSkill(memberId, teamAId, categoryXId, "SKVIEW 一般の資格", SkillStatus.ACTIVE, null, null);
            String base = "/api/v1/teams/" + teamAId + "/skills/";

            assertThat(callPath(supporterId, "GET", base + skillId)).isEqualTo(FORBIDDEN);
            assertThat(callPath(supporterId, "GET", base + skillId + "/certificate-url")).isEqualTo(FORBIDDEN);
            assertThat(callPath(supporterId, "DELETE", base + skillId)).isEqualTo(FORBIDDEN);
            assertThat(callPath(supporterId, "GET", base + 999_999_999L)).isEqualTo(SKILL_NOT_FOUND);
            assertThat(countSkills(memberId, "SKVIEW 一般の資格")).isEqualTo(1L);
        }

        private void assertMemberAllowed(Long actor) throws Exception {
            String name = "SKVIEW 登録 " + actor;
            assertThat(callEndpoint(actor, "GET /skills/me", teamAId).status()).as("me").isEqualTo(200);
            assertThat(performRegister(actor, teamAId, categoryXId, name).getStatus()).as("登録").isEqualTo(201);
            assertThat(countSkills(actor, name)).isEqualTo(1L);
            assertThat(callEndpoint(actor, "POST /skills/upload-url", teamAId).status()).as("upload-url").isEqualTo(200);
            assertThat(callEndpoint(actor, "GET /skill-categories", teamAId).status()).as("categories").isEqualTo(200);
        }

        private void assertMemberDenied(Long actor) throws Exception {
            String name = "SKVIEW 拒否登録 " + actor;
            for (String endpoint : MEMBER_ENDPOINTS) {
                Resp actual = "POST /skills".equals(endpoint)
                        ? toResp(performRegister(actor, teamAId, categoryXId, name))
                        : callEndpoint(actor, endpoint, teamAId);
                assertThat(actual).as("actor=%d endpoint=%s", actor, endpoint).isEqualTo(FORBIDDEN);
            }
            assertThat(countSkills(actor, name)).isZero();
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // B-AC3〜B-AC6: スキルマトリクスの行・表示名・確認待ち・未登録セル
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("B-AC3〜B-AC6: スキルマトリクス")
    class Matrix {

        @Test
        @DisplayName("B-AC3: 行は在籍メンバー（ADMIN・DEPUTY_ADMIN・MEMBER）全員。SUPPORTER・GUEST・脱退者・他チームは出ない")
        void BAC3_行は在籍メンバー全員() throws Exception {
            JsonNode data = matrix(adminId, teamAId);

            Set<Long> rowUserIds = new HashSet<>();
            data.path("rows").forEach(row -> rowUserIds.add(row.path("userId").asLong()));

            assertThat(rowUserIds).containsExactlyInAnyOrder(
                    adminId, deputyWithPermId, deputyWithoutPermId, memberId);
            assertThat(rowUserIds).doesNotContain(supporterId, guestId, departedId, outsiderId, sysAdminId);
        }

        @Test
        @DisplayName("B-AC4: 各行に表示名（users.display_name）が入る")
        void BAC4_各行に表示名() throws Exception {
            Map<Long, String> names = rowDisplayNames(matrix(adminId, teamAId));

            assertThat(names).containsEntry(adminId, "SKVIEW 管理者")
                    .containsEntry(deputyWithPermId, "SKVIEW 副管理者あり")
                    .containsEntry(deputyWithoutPermId, "SKVIEW 副管理者なし")
                    .containsEntry(memberId, "SKVIEW 一般");
        }

        @Test
        @DisplayName("B-AC4: 表示名を引けない在籍者（退会処理中で users が論理削除）は検索APIと同じ「不明なユーザー」")
        void BAC4_表示名を引けない在籍者は不明なユーザー() throws Exception {
            em.createNativeQuery("UPDATE users SET deleted_at = NOW() WHERE id = :id")
                    .setParameter("id", memberId)
                    .executeUpdate();
            em.flush();
            em.clear();

            Map<Long, String> names = rowDisplayNames(matrix(adminId, teamAId));

            assertThat(names).containsEntry(memberId, UNKNOWN_USER);
        }

        @Test
        @DisplayName("B-AC4: 行数を増やしても発行 SQL 数は一定（表示名・行の取得に N+1 が無い）")
        void BAC4_N足す1が無い() throws Exception {
            saveSkill(memberId, teamAId, categoryXId, "SKVIEW N1 既存", SkillStatus.ACTIVE, null, null);
            em.flush();
            em.clear();
            matrix(adminId, teamAId); // ウォームアップ（初回のみの読み込みを計測から外す）

            long small = countStatements(() -> matrix(adminId, teamAId));
            int smallRows = matrix(adminId, teamAId).path("rows").size();

            for (int i = 0; i < 5; i++) {
                Long extra = insertUser("skview-extra-" + i, "SKVIEW 追加" + i);
                MembershipTestHelper.insertMembership(em, extra, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
                saveSkill(extra, teamAId, categoryXId, "SKVIEW N1 追加" + i, SkillStatus.ACTIVE, null, null);
                saveSkill(extra, teamAId, categoryYId, "SKVIEW N1 追加Y" + i, SkillStatus.EXPIRED, null,
                        LocalDate.of(2020, 1, 1));
            }
            em.flush();
            em.clear();

            long large = countStatements(() -> matrix(adminId, teamAId));
            int largeRows = matrix(adminId, teamAId).path("rows").size();

            assertThat(largeRows).as("行が増えていること（計測の前提）").isEqualTo(smallRows + 5);
            assertThat(large).as("行 %d → %d で SQL 数 %d → %d", smallRows, largeRows, small, large)
                    .isEqualTo(small);
        }

        @Test
        @DisplayName("B-AC5: 確認待ち（PENDING_REVIEW）だけのセルは未登録（status null）。有効・期限切れはそのまま")
        void BAC5_確認待ちだけのセルは未登録() throws Exception {
            Long activeId = saveSkill(memberId, teamAId, categoryXId, "SKVIEW 有効", SkillStatus.ACTIVE,
                    null, LocalDate.of(2099, 12, 31));
            saveSkill(memberId, teamAId, categoryYId, "SKVIEW 確認待ち", SkillStatus.PENDING_REVIEW, null, null);
            Long expiredId = saveSkill(deputyWithoutPermId, teamAId, categoryYId, "SKVIEW 期限切れ",
                    SkillStatus.EXPIRED, null, LocalDate.of(2020, 1, 1));
            em.flush();
            em.clear();

            JsonNode data = matrix(adminId, teamAId);

            JsonNode memberActive = cell(data, memberId, categoryXId);
            assertThat(memberActive.path("status").asText()).isEqualTo("ACTIVE");
            assertThat(memberActive.path("memberSkillId").asLong()).isEqualTo(activeId);
            assertThat(memberActive.path("expiresAt").asText()).isEqualTo("2099-12-31");

            JsonNode memberPending = cell(data, memberId, categoryYId);
            assertThat(memberPending.path("status").isNull()).as("確認待ちのみ → status null").isTrue();
            assertThat(memberPending.path("memberSkillId").isNull()).isTrue();

            JsonNode deputyExpired = cell(data, deputyWithoutPermId, categoryYId);
            assertThat(deputyExpired.path("status").asText()).isEqualTo("EXPIRED");
            assertThat(deputyExpired.path("memberSkillId").asLong()).isEqualTo(expiredId);
        }

        @Test
        @DisplayName("B-AC6: 未登録セルは memberSkillId・status・expiresAt が null のオブジェクト（キーは全カテゴリぶん揃う）")
        void BAC6_未登録セルはnullのオブジェクト() throws Exception {
            JsonNode data = matrix(adminId, teamAId);

            JsonNode row = row(data, memberId);
            assertThat(row.path("cells").size()).as("全カテゴリぶんのセル").isEqualTo(2);
            for (Long categoryId : List.of(categoryXId, categoryYId)) {
                JsonNode cell = row.path("cells").path(String.valueOf(categoryId));
                assertThat(cell.isObject()).as("category=%d のセルはオブジェクト", categoryId).isTrue();
                for (String field : List.of("memberSkillId", "status", "expiresAt")) {
                    assertThat(cell.has(field)).as("キー %s が存在", field).isTrue();
                    assertThat(cell.get(field).isNull()).as("キー %s は null", field).isTrue();
                }
            }
        }

        private JsonNode matrix(Long actor, Long teamId) throws Exception {
            MockHttpServletResponse res = perform(actor, HttpMethod.GET,
                    "/api/v1/teams/" + teamId + "/skill-matrix", null);
            assertThat(res.getStatus()).as("skill-matrix の取得").isEqualTo(200);
            return json(res).path("data");
        }

        private Map<Long, String> rowDisplayNames(JsonNode data) {
            Map<Long, String> names = new HashMap<>();
            data.path("rows").forEach(r -> names.put(r.path("userId").asLong(), r.path("displayName").asText(null)));
            return names;
        }

        private JsonNode row(JsonNode data, Long userId) {
            for (JsonNode r : data.path("rows")) {
                if (r.path("userId").asLong() == userId) {
                    return r;
                }
            }
            throw new AssertionError("行が無い: userId=" + userId + " rows=" + data.path("rows"));
        }

        private JsonNode cell(JsonNode data, Long userId, Long categoryId) {
            return row(data, userId).path("cells").path(String.valueOf(categoryId));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // B-AC7: 検索結果の hasCertificate（判定式は MemberSkillResponse と同じ !isBlank）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("B-AC7: 検索APIの各要素に hasCertificate")
    class SearchHasCertificate {

        @Test
        @DisplayName("B-AC7: 証明書キーあり → hasCertificate=true")
        void BAC7_キーありはtrue() throws Exception {
            assertHasCertificate("skills/teams/1/users/1/certificates/a.pdf", true);
        }

        @Test
        @DisplayName("B-AC7: 証明書キー null → hasCertificate=false")
        void BAC7_nullはfalse() throws Exception {
            assertHasCertificate(null, false);
        }

        @Test
        @DisplayName("B-AC7: 証明書キー空文字 → hasCertificate=false")
        void BAC7_空文字はfalse() throws Exception {
            assertHasCertificate("", false);
        }

        @Test
        @DisplayName("B-AC7: 証明書キー空白のみ → hasCertificate=false")
        void BAC7_空白のみはfalse() throws Exception {
            assertHasCertificate("   ", false);
        }

        private void assertHasCertificate(String key, boolean expected) throws Exception {
            Long skillId = saveSkill(memberId, teamAId, categoryXId, "SKVIEW 証明書 " + (key == null ? "null" : key.length()),
                    SkillStatus.ACTIVE, key, null);
            em.flush();
            em.clear();

            MockHttpServletResponse res = perform(adminId, HttpMethod.GET,
                    "/api/v1/teams/" + teamAId + "/skills/search", null);
            assertThat(res.getStatus()).isEqualTo(200);

            JsonNode item = null;
            for (JsonNode n : json(res).path("data")) {
                if (n.path("memberSkillId").asLong() == skillId) {
                    item = n;
                }
            }
            assertThat(item).as("検索結果に対象資格が含まれる").isNotNull();
            assertThat(item.path("hasCertificate").isBoolean()).as("hasCertificate は boolean").isTrue();
            assertThat(item.path("hasCertificate").asBoolean()).isEqualTo(expected);
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // ヘルパー
    // ═════════════════════════════════════════════════════════════════════

    /** 拒否応答の比較単位（status・error.code・error.message）。 */
    private record Resp(int status, String code, String message) {
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        Object run() throws Exception;
    }

    private long countStatements(ThrowingRunnable action) throws Exception {
        Statistics stats = em.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
        stats.setStatisticsEnabled(true);
        em.flush();
        em.clear();
        stats.clear();
        action.run();
        return stats.getPrepareStatementCount();
    }

    private Resp callEndpoint(Long actor, String endpoint, Long teamId) throws Exception {
        String[] parts = endpoint.split(" ", 2);
        return callPath(actor, parts[0], "/api/v1/teams/" + teamId + parts[1]);
    }

    private Resp callPath(Long actor, String method, String path) throws Exception {
        return toResp(perform(actor, HttpMethod.valueOf(method), path, null));
    }

    private MockHttpServletResponse performRegister(Long actor, Long teamId, Long categoryId, String name)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("skillCategoryId", categoryId);
        body.put("name", name);
        return perform(actor, HttpMethod.POST, "/api/v1/teams/" + teamId + "/skills", body);
    }

    private MockHttpServletResponse perform(Long actor, HttpMethod method, String path, Map<String, Object> body)
            throws Exception {
        MockHttpServletRequestBuilder builder = request(method, path).header("Accept-Language", "ja");
        if (body != null) {
            builder.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body));
        }
        setAuth(actor);
        MockHttpServletResponse response = mockMvc.perform(builder).andReturn().getResponse();
        em.flush();
        em.clear();
        return response;
    }

    private Resp toResp(MockHttpServletResponse response) throws Exception {
        if (response.getContentAsByteArray().length == 0) {
            return new Resp(response.getStatus(), null, null);
        }
        JsonNode error = json(response).path("error");
        return new Resp(response.getStatus(),
                error.hasNonNull("code") ? error.get("code").asText() : null,
                error.hasNonNull("message") ? error.get("message").asText() : null);
    }

    private JsonNode json(MockHttpServletResponse response) throws Exception {
        return objectMapper.readTree(response.getContentAsString(StandardCharsets.UTF_8));
    }

    private void setAuth(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
    }

    /**
     * 登録マイグレーションの本文をそのまま実行して {@code MANAGE_MEMBER} のカタログ行を作る。
     * ADMIN ロール行は setUp の {@code insertUserRole} が作成済み（マイグレーションは roles を JOIN する）。
     */
    private void seedManageMemberCatalogFromMigration() {
        for (String sql : readMigrationStatements()) {
            em.createNativeQuery(sql).executeUpdate();
        }
        em.flush();
        Number catalogRows = (Number) em.createNativeQuery(
                        "SELECT COUNT(*) FROM permissions WHERE name = :name")
                .setParameter("name", MANAGE_MEMBER)
                .getSingleResult();
        assertThat(catalogRows.intValue())
                .as("フィクスチャの自己検証: migration 由来の MANAGE_MEMBER カタログ行が 1 行あること")
                .isEqualTo(1);
        Number deputyDefaults = (Number) em.createNativeQuery(
                        "SELECT COUNT(*) FROM role_permissions rp JOIN roles r ON r.id = rp.role_id "
                                + "JOIN permissions p ON p.id = rp.permission_id "
                                + "WHERE r.name = 'DEPUTY_ADMIN' AND p.name = :name")
                .setParameter("name", MANAGE_MEMBER)
                .getSingleResult();
        assertThat(deputyDefaults.intValue())
                .as("DEPUTY_ADMIN へは自動付与しない（F07.5 §2「MANAGE_MEMBER 権限を持つ場合」= 個別付与）")
                .isZero();
    }

    private List<String> readMigrationStatements() {
        String raw;
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(MIGRATION_RESOURCE)) {
            assertThat(in).as("migration ファイルが classpath 上に存在すること: " + MIGRATION_RESOURCE).isNotNull();
            raw = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("migration ファイルの読み出しに失敗した: " + MIGRATION_RESOURCE, e);
        }
        StringBuilder stripped = new StringBuilder();
        for (String line : raw.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("--")) {
                continue;
            }
            stripped.append(line).append('\n');
        }
        List<String> statements = new ArrayList<>();
        for (String part : stripped.toString().split(";")) {
            String sql = part.trim();
            if (!sql.isEmpty()) {
                statements.add(sql);
            }
        }
        return statements;
    }

    /** 権限グループ（target_role=DEPUTY_ADMIN）経由で MANAGE_MEMBER を個別付与する。 */
    private void grantManageMemberToDeputy(Long deputyId) {
        String groupName = "SKVIEW 名簿管理 " + (++seq);
        em.createNativeQuery("INSERT INTO permission_groups (team_id, organization_id, target_role, name, "
                        + "created_at, updated_at) VALUES (:tid, NULL, 'DEPUTY_ADMIN', :name, NOW(), NOW())")
                .setParameter("tid", teamAId)
                .setParameter("name", groupName)
                .executeUpdate();
        Long groupId = ((Number) em.createNativeQuery("SELECT id FROM permission_groups WHERE name = :name")
                .setParameter("name", groupName)
                .getSingleResult()).longValue();
        int linked = em.createNativeQuery("INSERT INTO permission_group_permissions (group_id, permission_id, created_at) "
                        + "SELECT :gid, p.id, NOW() FROM permissions p WHERE p.name = :perm")
                .setParameter("gid", groupId)
                .setParameter("perm", MANAGE_MEMBER)
                .executeUpdate();
        assertThat(linked).as("フィクスチャの自己検証: MANAGE_MEMBER を権限グループへ紐付けた").isEqualTo(1);
        em.createNativeQuery("INSERT INTO user_permission_groups (user_id, group_id, created_at) "
                        + "VALUES (:uid, :gid, NOW())")
                .setParameter("uid", deputyId)
                .setParameter("gid", groupId)
                .executeUpdate();
        em.flush();
        em.clear();
    }

    private Long saveSkill(Long userId, Long teamId, Long categoryId, String name, SkillStatus status,
                           String certificateKey, LocalDate expiresAt) {
        MemberSkillEntity saved = memberSkillRepository.save(MemberSkillEntity.builder()
                .userId(userId)
                .scopeType("TEAM")
                .scopeId(teamId)
                .skillCategoryId(categoryId)
                .name(name)
                .certificateS3Key(certificateKey)
                .expiresAt(expiresAt)
                .status(status)
                .build());
        return saved.getId();
    }

    private Long saveCategory(Long teamId, String name, int sortOrder) {
        SkillCategoryEntity category = skillCategoryRepository.save(SkillCategoryEntity.builder()
                .scopeType("TEAM")
                .scopeId(teamId)
                .name(name)
                .sortOrder(sortOrder)
                .isActive(true)
                .build());
        return category.getId();
    }

    private long countSkills(Long userId, String name) {
        return ((Number) em.createNativeQuery(
                        "SELECT COUNT(*) FROM member_skills WHERE user_id = :uid AND name = :name")
                .setParameter("uid", userId)
                .setParameter("name", name)
                .getSingleResult()).longValue();
    }

    private Long insertUser(String emailLocal, String displayName) {
        String email = emailLocal + "@example.com";
        em.createNativeQuery(
                        "INSERT INTO users ("
                                + "email, last_name, first_name, display_name, status, "
                                + "is_searchable, handle_searchable, contact_approval_required, "
                                + "online_visibility, dm_receive_from, encryption_key_version, "
                                + "locale, timezone, reporting_restricted, follow_list_visibility, "
                                + "care_notification_enabled, offline_only, "
                                + "created_at, updated_at) "
                                + "VALUES (:email, 'SKVIEW', 'テスト', :dn, 'ACTIVE', "
                                + "1, 1, 1, "
                                + "'NOBODY', 'ANYONE', 1, "
                                + "'ja', 'Asia/Tokyo', 0, 'PUBLIC', "
                                + "1, 0, "
                                + "NOW(), NOW())")
                .setParameter("email", email)
                .setParameter("dn", displayName)
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
                                + "CONCAT('s-', LEFT(REPLACE(UUID(),'-',''),8)), NOW(), NOW())")
                .setParameter("name", name)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM teams WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult()).longValue();
    }
}
