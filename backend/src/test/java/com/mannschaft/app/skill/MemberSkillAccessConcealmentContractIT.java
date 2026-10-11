package com.mannschaft.app.skill;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.common.storage.R2StorageService;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.skill.entity.MemberSkillEntity;
import com.mannschaft.app.skill.entity.SkillCategoryEntity;
import com.mannschaft.app.skill.event.SkillRegisteredEvent;
import com.mannschaft.app.skill.repository.MemberSkillRepository;
import com.mannschaft.app.skill.repository.SkillCategoryRepository;
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
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

/**
 * CMP-261010-2226: 資格 API（{@code SkillController} の個別 ID 4 操作と登録）の存在オラクル契約（試練 / red 先行）。
 *
 * <p>基準: {@code docs/security/01_authorization_baseline.md} §3.3.2。資格の所属スコープは
 * 資格実体から解決し、非メンバー（越境）への拒否は不在と status・{@code error.code}・{@code error.message}
 * まで完全一致させる。同一チーム内の権限不足は 403 {@code COMMON_002}（所属者には存在が既知）。</p>
 *
 * <p>判定は「同じ利用者が不在 ID を叩いた応答」との一致比較と、絶対値（404 / SKILL_002 /
 * 「資格が見つかりません」）の固定の両方で行う。相対比較だけだと、両方そろって別の値へ
 * ずれた変更を検出できないため。</p>
 *
 * <p>金型: {@code MemberSkillScopeContractIT}（{@code addFilters=false} + 実 MySQL + 手動 SecurityContext）。
 * 証明書の署名 URL 発行はテスト環境の外にあるため、具象型 {@code R2StorageService} を
 * {@code @MockitoBean} で置換する（インターフェース型で置換すると具象型の消費者の DI が壊れる。
 * {@code ChatAuthzScopeContractIT} と同一方針）。未認証 401（AC-9）はフィルター有効の
 * {@code OracleWave1HttpContractIT} が受け持つ。</p>
 *
 * <p>本クラスは {@code @Transactional} のため登録イベントの配送（AFTER_COMMIT）は観測できない。
 * 「登録イベント未発行」は {@code @RecordApplicationEvents} で publish 自体の有無を検証する。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@RecordApplicationEvents
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("CMP-261010-2226 資格API 存在オラクル契約（不在と越境の完全一致）")
class MemberSkillAccessConcealmentContractIT extends AbstractMySqlIntegrationTest {

    private static final long MISSING_SKILL_ID = 999_999_999L;
    private static final String CERT_KEY = "skills/teams/0/users/0/certificates/conceal-test.pdf";
    private static final String CERT_URL = "https://storage.example.invalid/conceal-test.pdf";

    /** 不在応答の絶対値（{@code SkillErrorCode.SKILL_002}）。 */
    private static final Resp CONCEALED = new Resp(404, "SKILL_002", "資格が見つかりません");
    /** 同一チーム内の権限不足（{@code CommonErrorCode.COMMON_002}）。 */
    private static final Resp FORBIDDEN = new Resp(403, "COMMON_002", "この操作を行う権限がありません");
    /** カテゴリ不在（{@code SkillErrorCode.SKILL_001}）。 */
    private static final Resp CATEGORY_NOT_FOUND = new Resp(404, "SKILL_001", "カテゴリが見つかりません");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MemberSkillRepository memberSkillRepository;

    @Autowired
    private SkillCategoryRepository skillCategoryRepository;

    @Autowired
    private ApplicationEvents applicationEvents;

    @MockitoBean
    private R2StorageService storageService;

    @PersistenceContext
    private EntityManager em;

    private Long teamAId;
    private Long teamBId;

    private Long ownerId;        // teamA メンバー・資格の所有者本人
    private Long otherMemberId;  // teamA メンバー（本人でも管理者でもない）
    private Long adminAId;       // teamA ADMIN
    private Long deputyAId;      // teamA DEPUTY_ADMIN
    private Long adminBId;       // teamB ADMIN（teamA には無所属）
    private Long outsiderId;     // どこにも所属しない利用者
    private Long sysAdminId;     // SYSTEM_ADMIN（どこにも所属しない）
    private Long departedOwnerId; // teamA を脱退済みの資格所有者

    private Long skillAId;          // teamA・ownerId 所有・証明書あり
    private Long skillANoCertId;    // teamA・ownerId 所有・証明書なし
    private Long skillBId;          // teamB・adminBId 所有
    private Long departedSkillId;   // teamA・departedOwnerId 所有・証明書あり

    @BeforeEach
    void setUp() {
        teamAId = insertTeam("SKCONCEAL チームA");
        teamBId = insertTeam("SKCONCEAL チームB");

        ownerId = insertUser("skconceal-owner@example.com");
        otherMemberId = insertUser("skconceal-other@example.com");
        adminAId = insertUser("skconceal-admin-a@example.com");
        deputyAId = insertUser("skconceal-deputy-a@example.com");
        adminBId = insertUser("skconceal-admin-b@example.com");
        outsiderId = insertUser("skconceal-outsider@example.com");
        sysAdminId = insertUser("skconceal-sysadmin@example.com");
        departedOwnerId = insertUser("skconceal-departed@example.com");

        MembershipTestHelper.insertMembership(em, ownerId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        MembershipTestHelper.insertMembership(em, otherMemberId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        MembershipTestHelper.insertMembership(em, adminAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminAId, "ADMIN", teamAId, null);
        MembershipTestHelper.insertMembership(em, deputyAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, deputyAId, "DEPUTY_ADMIN", teamAId, null);
        MembershipTestHelper.insertMembership(em, adminBId, ScopeType.TEAM, teamBId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminBId, "ADMIN", teamBId, null);
        MembershipTestHelper.insertUserRole(em, sysAdminId, "SYSTEM_ADMIN", null, null);
        MembershipTestHelper.insertMembership(em, departedOwnerId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);

        skillAId = saveSkill(ownerId, teamAId, "SKCONCEAL 普通救命講習", CERT_KEY);
        skillANoCertId = saveSkill(ownerId, teamAId, "SKCONCEAL 証明書なし資格", null);
        skillBId = saveSkill(adminBId, teamBId, "SKCONCEAL チームBの資格", CERT_KEY);
        departedSkillId = saveSkill(departedOwnerId, teamAId, "SKCONCEAL 脱退者の資格", CERT_KEY);

        // 資格を持ったまま teamA を脱退した状態（memberships.left_at が埋まる）。
        em.createNativeQuery("UPDATE memberships SET left_at = NOW(), updated_at = NOW() "
                        + "WHERE user_id = :uid AND scope_type = 'TEAM' AND scope_id = :sid")
                .setParameter("uid", departedOwnerId)
                .setParameter("sid", teamAId)
                .executeUpdate();

        em.flush();
        em.clear();

        given(storageService.generateDownloadUrl(anyString(), any(Duration.class))).willReturn(CERT_URL);
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-1〜AC-4: 越境（非メンバー）は不在と完全一致
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("越境（非メンバー）の拒否は不在と完全一致する")
    class CrossScopeConcealment {

        @Test
        @DisplayName("AC-1: 非メンバーが被害チームの実在資格をGETすると、同じ利用者の不在ID応答と完全一致（404/SKILL_002）")
        void AC1_非メンバーのGETは不在と完全一致() throws Exception {
            String before = snapshot(skillAId);

            Resp missing = call(outsiderId, "GET", teamAId, MISSING_SKILL_ID);
            Resp existing = call(outsiderId, "GET", teamAId, skillAId);

            assertThat(missing).isEqualTo(CONCEALED);
            assertThat(existing).isEqualTo(missing);
            assertThat(snapshot(skillAId)).isEqualTo(before);
        }

        @ParameterizedTest(name = "AC-2: 非メンバーの{0}は不在と完全一致・副作用なし")
        @ValueSource(strings = {"PUT", "DELETE", "CERTIFICATE"})
        @DisplayName("AC-2: 非メンバーのPUT/DELETE/certificate-urlは不在と完全一致し、資格不変・未削除・storage未呼び出し")
        void AC2_非メンバーの更新削除証明書は不在と完全一致し副作用なし(String operation) throws Exception {
            String before = snapshot(skillAId);

            Resp missing = call(outsiderId, operation, teamAId, MISSING_SKILL_ID);
            Resp existing = call(outsiderId, operation, teamAId, skillAId);

            assertThat(missing).isEqualTo(CONCEALED);
            assertThat(existing).isEqualTo(missing);
            assertThat(snapshot(skillAId)).isEqualTo(before);
            verify(storageService, never()).generateDownloadUrl(any(), any());
        }

        @ParameterizedTest(name = "AC-3: 他チームADMINがpath=被害チームで{0}すると不在と完全一致")
        @ValueSource(strings = {"GET", "PUT", "DELETE", "CERTIFICATE"})
        @DisplayName("AC-3: 他チームのADMINがpath=被害チームで実在IDを叩いても不在と完全一致")
        void AC3_他チームADMINが被害チームpathで叩いても不在と完全一致(String operation) throws Exception {
            String before = snapshot(skillAId);

            Resp missing = call(adminBId, operation, teamAId, MISSING_SKILL_ID);
            Resp existing = call(adminBId, operation, teamAId, skillAId);

            assertThat(missing).isEqualTo(CONCEALED);
            assertThat(existing).isEqualTo(missing);
            assertThat(snapshot(skillAId)).isEqualTo(before);
            verify(storageService, never()).generateDownloadUrl(any(), any());
        }

        @ParameterizedTest(name = "AC-4: 他チームADMINがpath=自チームで他チーム資格を{0}すると404 SKILL_002")
        @ValueSource(strings = {"GET", "PUT", "DELETE", "CERTIFICATE"})
        @DisplayName("AC-4: 他チームのADMINがpath=自チームで他チームの資格IDを叩くと404 SKILL_002（維持）")
        void AC4_自チームpathで他チーム資格を叩くと不在(String operation) throws Exception {
            String before = snapshot(skillAId);

            Resp missing = call(adminBId, operation, teamBId, MISSING_SKILL_ID);
            Resp existing = call(adminBId, operation, teamBId, skillAId);

            assertThat(missing).isEqualTo(CONCEALED);
            assertThat(existing).isEqualTo(missing);
            assertThat(snapshot(skillAId)).isEqualTo(before);
            verify(storageService, never()).generateDownloadUrl(any(), any());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-5〜AC-8: 同一チーム内の権限判定（ゲート仕様どおり）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("同一チーム内の権限判定")
    class SameScopeAuthorization {

        @ParameterizedTest(name = "AC-5: 同チーム一般メンバー（本人でない）の{0}は403 COMMON_002")
        @ValueSource(strings = {"GET", "PUT", "DELETE", "CERTIFICATE"})
        @DisplayName("AC-5: 同チーム一般メンバー（本人でない）は403 COMMON_002、資格不変・storage未呼び出し")
        void AC5_同チーム一般メンバーは403(String operation) throws Exception {
            String before = snapshot(skillAId);

            Resp actual = call(otherMemberId, operation, teamAId, skillAId);

            assertThat(actual).isEqualTo(FORBIDDEN);
            assertThat(snapshot(skillAId)).isEqualTo(before);
            verify(storageService, never()).generateDownloadUrl(any(), any());
        }

        @Test
        @DisplayName("AC-6: 本人はGET 200・PUT 200・certificate-url 200（証明書登録済み）")
        void AC6_本人はGET_PUT_証明書が200() throws Exception {
            assertThat(call(ownerId, "GET", teamAId, skillAId).status()).isEqualTo(200);

            MockHttpServletResponse cert = perform(ownerId, "CERTIFICATE", teamAId, skillAId, null);
            assertThat(cert.getStatus()).isEqualTo(200);
            assertThat(json(cert).path("data").asText()).isEqualTo(CERT_URL);

            MockHttpServletResponse put = perform(ownerId, "PUT", teamAId, skillAId,
                    updateBody("SKCONCEAL 本人更新", currentVersion(skillAId)));
            assertThat(put.getStatus()).isEqualTo(200);
            assertThat(json(put).path("data").path("name").asText()).isEqualTo("SKCONCEAL 本人更新");
        }

        @Test
        @DisplayName("AC-6: 本人はDELETE 204（論理削除される）")
        void AC6_本人はDELETEが204() throws Exception {
            assertThat(call(ownerId, "DELETE", teamAId, skillAId).status()).isEqualTo(204);
            assertThat(isDeleted(skillAId)).isTrue();
        }

        @Test
        @DisplayName("AC-6: 本人のPUTでversion不一致は409 SKILL_006、資格・version不変")
        void AC6_本人のversion不一致は409で不変() throws Exception {
            String before = snapshot(skillAId);
            long staleVersion = currentVersion(skillAId) + 5;

            Resp actual = call(ownerId, "PUT", teamAId, skillAId, updateBody("SKCONCEAL 競合更新", staleVersion));

            assertThat(actual).isEqualTo(new Resp(409, "SKILL_006", "バージョンが一致しません（楽観的ロック）"));
            assertThat(snapshot(skillAId)).isEqualTo(before);
        }

        @ParameterizedTest(name = "AC-7: {0} は他人の資格に GET/PUT 200・certificate-url 200・DELETE 204")
        @ValueSource(strings = {"ADMIN", "DEPUTY_ADMIN", "SYSTEM_ADMIN"})
        @DisplayName("AC-7: ADMIN・DEPUTY_ADMIN・SYSTEM_ADMIN は他人の資格に GET/PUT 200・certificate-url 200・DELETE 204")
        void AC7_管理者とSYSTEM_ADMINは他人の資格を操作できる(String role) throws Exception {
            Long actor = switch (role) {
                case "ADMIN" -> adminAId;
                case "DEPUTY_ADMIN" -> deputyAId;
                case "SYSTEM_ADMIN" -> sysAdminId;
                default -> throw new IllegalArgumentException(role);
            };

            assertThat(call(actor, "GET", teamAId, skillAId).status()).isEqualTo(200);

            MockHttpServletResponse cert = perform(actor, "CERTIFICATE", teamAId, skillAId, null);
            assertThat(cert.getStatus()).isEqualTo(200);
            assertThat(json(cert).path("data").asText()).isEqualTo(CERT_URL);

            MockHttpServletResponse put = perform(actor, "PUT", teamAId, skillAId,
                    updateBody("SKCONCEAL 管理者更新 " + role, currentVersion(skillAId)));
            assertThat(put.getStatus()).isEqualTo(200);

            assertThat(call(actor, "DELETE", teamAId, skillAId).status()).isEqualTo(204);
            assertThat(isDeleted(skillAId)).isTrue();
        }

        @Test
        @DisplayName("AC-8: 脱退済み本人は所有者として通過する（ゲート仕様: 本人の現在の所属は問わない）")
        void AC8_脱退済み本人は所有者として通過する() throws Exception {
            assertThat(call(departedOwnerId, "GET", teamAId, departedSkillId).status()).isEqualTo(200);
            assertThat(call(departedOwnerId, "CERTIFICATE", teamAId, departedSkillId).status()).isEqualTo(200);
            assertThat(call(departedOwnerId, "PUT", teamAId, departedSkillId,
                    updateBody("SKCONCEAL 脱退者更新", currentVersion(departedSkillId))).status()).isEqualTo(200);
            assertThat(call(departedOwnerId, "DELETE", teamAId, departedSkillId).status()).isEqualTo(204);
            assertThat(isDeleted(departedSkillId)).isTrue();
        }

        @Test
        @DisplayName("AC-8: 脱退済み利用者が他人の資格を叩くと非メンバーとして不在と完全一致")
        void AC8_脱退済み利用者の他人資格は不在と完全一致() throws Exception {
            Resp missing = call(departedOwnerId, "GET", teamAId, MISSING_SKILL_ID);
            Resp existing = call(departedOwnerId, "GET", teamAId, skillAId);

            assertThat(missing).isEqualTo(CONCEALED);
            assertThat(existing).isEqualTo(missing);
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-10 / AC-11: 親チーム・資格・証明書の不在
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("親チーム・資格・証明書の不在")
    class ParentAndResourceAbsence {

        @ParameterizedTest(name = "AC-10: 親チーム論理削除済みなら {0} は本人・SYSTEM_ADMIN・ADMIN とも404 SKILL_002")
        @ValueSource(strings = {"GET", "PUT", "DELETE", "CERTIFICATE"})
        @DisplayName("AC-10: 親チーム論理削除済みなら本人・SYSTEM_ADMIN含め404 SKILL_002、副作用なし")
        void AC10_親チーム論理削除済みは全員不在(String operation) throws Exception {
            softDeleteTeam(teamAId);
            String before = snapshot(skillAId);

            for (Long actor : List.of(ownerId, sysAdminId, adminAId)) {
                assertThat(call(actor, operation, teamAId, skillAId))
                        .as("actor=%d operation=%s", actor, operation)
                        .isEqualTo(CONCEALED);
            }
            assertThat(snapshot(skillAId)).isEqualTo(before);
            verify(storageService, never()).generateDownloadUrl(any(), any());
        }

        @ParameterizedTest(name = "AC-11: 削除済み資格への {0} は404 SKILL_002")
        @ValueSource(strings = {"GET", "PUT", "DELETE", "CERTIFICATE"})
        @DisplayName("AC-11: 削除済み資格は本人・ADMIN・SYSTEM_ADMINとも404 SKILL_002")
        void AC11_削除済み資格は不在(String operation) throws Exception {
            em.createNativeQuery("UPDATE member_skills SET deleted_at = NOW() WHERE id = :id")
                    .setParameter("id", skillAId)
                    .executeUpdate();
            em.clear();
            String before = snapshot(skillAId);

            for (Long actor : List.of(ownerId, adminAId, sysAdminId)) {
                assertThat(call(actor, operation, teamAId, skillAId))
                        .as("actor=%d operation=%s", actor, operation)
                        .isEqualTo(CONCEALED);
            }
            assertThat(snapshot(skillAId)).isEqualTo(before);
            verify(storageService, never()).generateDownloadUrl(any(), any());
        }

        @Test
        @DisplayName("AC-11: 証明書キーnullは許可主体（本人・ADMIN・SYSTEM_ADMIN）でも404 SKILL_002、storage未呼び出し")
        void AC11_証明書キーnullは許可主体でも不在() throws Exception {
            for (Long actor : List.of(ownerId, adminAId, sysAdminId)) {
                assertThat(call(actor, "CERTIFICATE", teamAId, skillANoCertId))
                        .as("actor=%d", actor)
                        .isEqualTo(CONCEALED);
            }
            verify(storageService, never()).generateDownloadUrl(any(), any());
        }

        @Test
        @DisplayName("AC-11: certificate-urlの拒否応答は証明書キーの有無で変わらない（非メンバー=不在一致・同チーム一般=403）")
        void AC11_証明書拒否応答はキー有無で不変() throws Exception {
            Resp outsiderWithKey = call(outsiderId, "CERTIFICATE", teamAId, skillAId);
            Resp outsiderWithoutKey = call(outsiderId, "CERTIFICATE", teamAId, skillANoCertId);
            assertThat(outsiderWithKey).isEqualTo(CONCEALED);
            assertThat(outsiderWithoutKey).isEqualTo(outsiderWithKey);

            Resp memberWithKey = call(otherMemberId, "CERTIFICATE", teamAId, skillAId);
            Resp memberWithoutKey = call(otherMemberId, "CERTIFICATE", teamAId, skillANoCertId);
            assertThat(memberWithKey).isEqualTo(FORBIDDEN);
            assertThat(memberWithoutKey).isEqualTo(memberWithKey);

            verify(storageService, never()).generateDownloadUrl(any(), any());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-12: registerSkill のカテゴリはスコープ（種別・ID）一致を要求する
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("AC-12: 資格登録のカテゴリ・スコープ突合")
    class RegisterCategoryScope {

        @Test
        @DisplayName("AC-12: 他チームのカテゴリを指定すると404 SKILL_001、資格未保存・登録イベント未発行")
        void AC12_他チームカテゴリは404で未保存() throws Exception {
            Long categoryId = saveCategory("TEAM", teamBId, "SKCONCEAL Bカテゴリ", true, false);
            assertRegisterRejected(categoryId, "SKCONCEAL 越境登録", CATEGORY_NOT_FOUND);
        }

        @Test
        @DisplayName("AC-12: 他チームの非activeカテゴリでも409ではなく404 SKILL_001（スコープ判定はactive判定より先）")
        void AC12_他チーム非activeカテゴリも404() throws Exception {
            Long categoryId = saveCategory("TEAM", teamBId, "SKCONCEAL B休止カテゴリ", false, false);
            assertRegisterRejected(categoryId, "SKCONCEAL 越境休止登録", CATEGORY_NOT_FOUND);
        }

        @Test
        @DisplayName("AC-12: 異種スコープ（同数値IDのORGANIZATION）のカテゴリは404 SKILL_001、未保存・未発行")
        void AC12_異種スコープ同数値IDカテゴリは404() throws Exception {
            Long categoryId = saveCategory("ORGANIZATION", teamAId, "SKCONCEAL 組織カテゴリ", true, false);
            assertRegisterRejected(categoryId, "SKCONCEAL 異種スコープ登録", CATEGORY_NOT_FOUND);
        }

        @Test
        @DisplayName("AC-12: 同スコープの削除済みカテゴリは404 SKILL_001、未保存・未発行")
        void AC12_削除済みカテゴリは404() throws Exception {
            Long categoryId = saveCategory("TEAM", teamAId, "SKCONCEAL 削除済みカテゴリ", true, true);
            assertRegisterRejected(categoryId, "SKCONCEAL 削除済みカテゴリ登録", CATEGORY_NOT_FOUND);
        }

        @Test
        @DisplayName("AC-12: 同スコープの非activeカテゴリは409 SKILL_010、未保存・未発行")
        void AC12_同スコープ非activeカテゴリは409() throws Exception {
            Long categoryId = saveCategory("TEAM", teamAId, "SKCONCEAL 休止カテゴリ", false, false);
            assertRegisterRejected(categoryId, "SKCONCEAL 休止カテゴリ登録",
                    new Resp(409, "SKILL_010", "このカテゴリは現在利用できません"));
        }

        @Test
        @DisplayName("AC-12: 同スコープのactiveカテゴリは201で保存され、登録イベントが1件発行される")
        void AC12_同スコープactiveカテゴリは201() throws Exception {
            Long categoryId = saveCategory("TEAM", teamAId, "SKCONCEAL 有効カテゴリ", true, false);
            String name = "SKCONCEAL 正当登録";

            MockHttpServletResponse res = performRegister(ownerId, teamAId, categoryId, name);

            assertThat(res.getStatus()).isEqualTo(201);
            assertThat(json(res).path("data").path("skillCategoryId").asLong()).isEqualTo(categoryId);
            assertThat(countSkills(ownerId, name)).isEqualTo(1L);
            assertThat(applicationEvents.stream(SkillRegisteredEvent.class).count()).isEqualTo(1L);
        }

        private void assertRegisterRejected(Long categoryId, String name, Resp expected) throws Exception {
            Resp actual = toResp(performRegister(ownerId, teamAId, categoryId, name));

            assertThat(actual).isEqualTo(expected);
            assertThat(countSkills(ownerId, name)).isZero();
            assertThat(applicationEvents.stream(SkillRegisteredEvent.class).count()).isZero();
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-13: 本是正の対象外エンドポイントの既存挙動の固定（入口ガードの 403 COMMON_002 のまま）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("AC-13: 対象外エンドポイントは非メンバー・権限不足とも従来どおり 403 COMMON_002")
    class UnchangedEntryGuardEndpoints {

        @ParameterizedTest(name = "AC-13: 非メンバーの{0}は403 COMMON_002（不在化しない）")
        @ValueSource(strings = {
                "GET /skills/me", "POST /skills/upload-url", "GET /skills/search",
                "GET /skill-matrix", "GET /skills/export"})
        @DisplayName("AC-13: 非メンバーが me / upload-url / search / skill-matrix / export を叩くと従来どおり 403 COMMON_002")
        void AC13_非メンバーは入口ガードで403(String endpoint) throws Exception {
            assertThat(callEndpoint(outsiderId, endpoint, teamAId)).isEqualTo(FORBIDDEN);
            verify(storageService, never()).generateUploadUrl(any(), any(), any());
        }

        @Test
        @DisplayName("AC-13: 非管理者メンバーの export は従来どおり 403 COMMON_002（管理者判定の入口ガード）")
        void AC13_非管理者メンバーのexportは403() throws Exception {
            assertThat(callEndpoint(otherMemberId, "GET /skills/export", teamAId)).isEqualTo(FORBIDDEN);
        }

        @ParameterizedTest(name = "AC-13: {0} の verify は403 COMMON_002、資格は不変")
        @ValueSource(strings = {"member", "outsider"})
        @DisplayName("AC-13: 非管理者（同チーム一般メンバー・非メンバー）の verify は従来どおり 403 COMMON_002、資格不変")
        void AC13_非管理者のverifyは403(String actorKind) throws Exception {
            Long actor = "member".equals(actorKind) ? otherMemberId : outsiderId;
            String before = snapshot(skillAId);

            Resp actual = callEndpoint(actor, "PATCH /skills/" + skillAId + "/verify", teamAId);

            assertThat(actual).isEqualTo(FORBIDDEN);
            assertThat(snapshot(skillAId)).isEqualTo(before);
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // ヘルパー
    // ═════════════════════════════════════════════════════════════════════

    /** 拒否応答の比較単位（status・error.code・error.message）。 */
    private record Resp(int status, String code, String message) {
    }

    private Resp call(Long actor, String operation, Long teamId, long skillId) throws Exception {
        Map<String, Object> body = "PUT".equals(operation)
                ? updateBody("SKCONCEAL 改変試行", versionOrZero(skillId))
                : null;
        return call(actor, operation, teamId, skillId, body);
    }

    private Resp call(Long actor, String operation, Long teamId, long skillId, Map<String, Object> body)
            throws Exception {
        return toResp(perform(actor, operation, teamId, skillId, body));
    }

    private MockHttpServletResponse perform(Long actor, String operation, Long teamId, long skillId,
                                            Map<String, Object> body) throws Exception {
        String base = "/api/v1/teams/" + teamId + "/skills/" + skillId;
        MockHttpServletRequestBuilder builder = switch (operation) {
            case "GET" -> request(HttpMethod.GET, base);
            case "PUT" -> request(HttpMethod.PUT, base);
            case "DELETE" -> request(HttpMethod.DELETE, base);
            case "CERTIFICATE" -> request(HttpMethod.GET, base + "/certificate-url");
            default -> throw new IllegalArgumentException(operation);
        };
        if (body != null) {
            builder.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body));
        }
        setAuth(actor);
        MockHttpServletResponse response = mockMvc.perform(builder.header("Accept-Language", "ja"))
                .andReturn().getResponse();
        em.flush();
        em.clear();
        return response;
    }

    private Resp callEndpoint(Long actor, String endpoint, Long teamId) throws Exception {
        String[] parts = endpoint.split(" ", 2);
        String path = "/api/v1/teams/" + teamId + parts[1];
        setAuth(actor);
        MockHttpServletResponse response = mockMvc.perform(
                        request(HttpMethod.valueOf(parts[0]), path).header("Accept-Language", "ja"))
                .andReturn().getResponse();
        em.flush();
        em.clear();
        return toResp(response);
    }

    private MockHttpServletResponse performRegister(Long actor, Long teamId, Long categoryId, String name)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("skillCategoryId", categoryId);
        body.put("name", name);
        setAuth(actor);
        MockHttpServletResponse response = mockMvc.perform(request(HttpMethod.POST, "/api/v1/teams/" + teamId + "/skills")
                        .header("Accept-Language", "ja")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn().getResponse();
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

    private Map<String, Object> updateBody(String name, long version) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("version", version);
        return body;
    }

    private void setAuth(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
    }

    private Long saveSkill(Long userId, Long teamId, String name, String certificateKey) {
        MemberSkillEntity saved = memberSkillRepository.save(MemberSkillEntity.builder()
                .userId(userId)
                .scopeType("TEAM")
                .scopeId(teamId)
                .name(name)
                .certificateS3Key(certificateKey)
                .status(SkillStatus.ACTIVE)
                .build());
        return saved.getId();
    }

    private Long saveCategory(String scopeType, Long scopeId, String name, boolean active, boolean deleted) {
        SkillCategoryEntity category = skillCategoryRepository.save(SkillCategoryEntity.builder()
                .scopeType(scopeType)
                .scopeId(scopeId)
                .name(name)
                .isActive(active)
                .createdBy(ownerId)
                .build());
        if (deleted) {
            category.softDelete();
            skillCategoryRepository.save(category);
        }
        em.flush();
        em.clear();
        return category.getId();
    }

    /**
     * 資格行の状態（名前・version・削除日時・ステータス）を DB から直接読む。
     * {@code @SQLRestriction} を避けるため native で読み、削除済み行も比較できるようにする。
     */
    private String snapshot(long skillId) {
        em.flush();
        em.clear();
        Object row = em.createNativeQuery(
                        "SELECT CONCAT_WS('|', name, version, IFNULL(CAST(deleted_at AS CHAR), 'NULL'), status, "
                                + "IFNULL(certificate_s3_key, 'NULL')) FROM member_skills WHERE id = :id")
                .setParameter("id", skillId)
                .getSingleResult();
        return String.valueOf(row);
    }

    private boolean isDeleted(long skillId) {
        Object deletedAt = em.createNativeQuery("SELECT deleted_at FROM member_skills WHERE id = :id")
                .setParameter("id", skillId)
                .getSingleResult();
        return deletedAt != null;
    }

    private long currentVersion(long skillId) {
        return ((Number) em.createNativeQuery("SELECT version FROM member_skills WHERE id = :id")
                .setParameter("id", skillId)
                .getSingleResult()).longValue();
    }

    private long versionOrZero(long skillId) {
        List<?> rows = em.createNativeQuery("SELECT version FROM member_skills WHERE id = :id")
                .setParameter("id", skillId)
                .getResultList();
        return rows.isEmpty() || rows.get(0) == null ? 0L : ((Number) rows.get(0)).longValue();
    }

    private long countSkills(Long userId, String name) {
        return ((Number) em.createNativeQuery(
                        "SELECT COUNT(*) FROM member_skills WHERE user_id = :uid AND name = :name")
                .setParameter("uid", userId)
                .setParameter("name", name)
                .getSingleResult()).longValue();
    }

    private void softDeleteTeam(Long teamId) {
        em.createNativeQuery("UPDATE teams SET deleted_at = NOW() WHERE id = :id")
                .setParameter("id", teamId)
                .executeUpdate();
        em.clear();
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
                                + "VALUES (:email, 'SKCONCEAL', 'テスト', 'SKCONCEAL テスト', 'ACTIVE', "
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
                                + "CONCAT('s-', LEFT(REPLACE(UUID(),'-',''),8)), NOW(), NOW())")
                .setParameter("name", name)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM teams WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult()).longValue();
    }
}
