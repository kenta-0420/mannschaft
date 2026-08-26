package com.mannschaft.app.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.organization.entity.OrganizationEntity;
import com.mannschaft.app.organization.repository.OrganizationRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.team.entity.TeamEntity;
import com.mannschaft.app.team.repository.TeamRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * システム管理者ダッシュボード API のレスポンス契約テスト（試練 / テスト先行）。
 *
 * <p><b>背景</b>: {@code SystemAdminDashboardController} の organizations / teams / users 3 EP は
 * {@code Page<Entity>} を直返ししている。特に users EP は {@link UserEntity} をそのまま返しており、
 * {@code passwordHash} や氏名・電話番号・生年月日等の PII、内部フラグ（{@code encryptionKeyVersion} /
 * {@code reportingRestricted} / {@code purgedAt}）がシステム管理画面のレスポンスに全て露出する。
 * 後続 PR で DTO 化して<b>許可リスト</b>方式に絞る予定であり、本テストはその契約を実装より前に固定する（red 先行）。</p>
 *
 * <h2>固定する契約</h2>
 * <ul>
 *   <li><b>AC-1</b>: users EP のレスポンス要素に<b>禁止キーが一切出現しない</b>（passwordHash・氏名・カナ・
 *       電話番号・郵便番号・生年月日・性別・都道府県/市区町村コード・各種 *Hash・encryptionKeyVersion・
 *       reportingRestricted・purgedAt）。かつ要素のキー集合が<b>許可リストの部分集合</b>であること。
 *       seed には PII を実際に埋めた {@link UserEntity} を用いる（PII 無しユーザーで通る偽 green を防ぐ）。</li>
 *   <li><b>AC-2</b>: organizations / teams / users の 3 EP が {@code data.content} / {@code data.totalElements} /
 *       {@code data.totalPages} の Page 形を維持し、organizations / teams の要素は {@code archivedAt} キーを持つ。</li>
 *   <li><b>AC-11</b>: 認可回帰。system-admin ダッシュボード EP は未認証で 401・SYSTEM_ADMIN でないユーザーで 403。</li>
 * </ul>
 *
 * <p><b>現状の期待挙動</b>: users EP は Entity 直返しのため禁止キーが多数出現し、AC-1 は実装前は <b>red</b> になる。
 * AC-2/AC-11 は現行挙動の回帰ガードであり green でよい。</p>
 *
 * <p>金型: {@code ResidentAuthzContractTest}（実 MySQL + 実 Security フィルタ + {@code @WithMockUser}）。
 * SYSTEM_ADMIN 判定は {@code SecurityConfig} のパス単位認可（{@code /api/v1/system-admin/** → hasRole}）が
 * {@code @WithMockUser(roles = "SYSTEM_ADMIN")} の権限で満たされる（DB ロール seed 不要）。</p>
 */
@AutoConfigureMockMvc
@Transactional
@DisplayName("システム管理ダッシュボード API レスポンス契約テスト（試練）")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class SystemAdminDashboardResponseContractTrialTest extends AbstractMySqlIntegrationTest {

    /** users EP レスポンス要素で許可されるキー（許可リスト方式）。 */
    private static final Set<String> ALLOWED_USER_KEYS = Set.of(
            "id", "email", "displayName", "contactHandle", "status", "locale",
            "timezone", "lastLoginAt", "archivedAt", "createdAt", "updatedAt");

    /** 絶対に出現してはならない PII / 内部フラグのキー。 */
    private static final Set<String> FORBIDDEN_USER_KEYS = Set.of(
            "passwordHash", "lastName", "firstName", "lastNameKana", "firstNameKana",
            "phoneNumber", "postalCode", "birthDate", "gender", "prefectureCode", "cityCode",
            "lastNameHash", "firstNameHash", "phoneNumberHash", "genderHash",
            "prefectureCodeHash", "cityCodeHash", "birthDateHash",
            "encryptionKeyVersion", "reportingRestricted", "purgedAt");

    private static final AtomicInteger SLUG_SEQ = new AtomicInteger(0);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private TeamRepository teamRepository;

    private String seededEmail;

    @BeforeEach
    void setUp() {
        int seq = SLUG_SEQ.incrementAndGet();
        seededEmail = "sysadmin-resp-" + seq + "@example.com";

        // PII を実際に埋めたユーザーを保存する（暗号化カラムは EncryptedStringConverter が透過的に暗号化）。
        userRepository.save(UserEntity.builder()
                .email(seededEmail)
                .passwordHash("$2a$10$abcdefghijklmnopqrstuv")
                .lastName("山田")
                .firstName("太郎")
                .lastNameKana("ヤマダ")
                .firstNameKana("タロウ")
                .displayName("やまだ")
                .contactHandle("yamada_" + seq)
                .isSearchable(true)
                .phoneNumber("09012345678")
                .postalCode("1000001")
                .lastNameHash("h".repeat(64))
                .firstNameHash("i".repeat(64))
                .phoneNumberHash("j".repeat(64))
                .locale("ja")
                .timezone("Asia/Tokyo")
                .status(UserEntity.UserStatus.ACTIVE)
                .birthDate("1990-01-01")
                .gender("MALE")
                .genderHash("k".repeat(64))
                .prefectureCode("13")
                .prefectureCodeHash("l".repeat(64))
                .cityCode("13101")
                .cityCodeHash("m".repeat(64))
                .birthDateHash("n".repeat(64))
                .birthYear(1990)
                .build());

        organizationRepository.save(OrganizationEntity.builder()
                .slug("sysadmin-resp-org-" + seq)
                .name("SYSADMINRESP 組織")
                .orgType(OrganizationEntity.OrgType.OTHER)
                .visibility(OrganizationEntity.Visibility.PUBLIC)
                .hierarchyVisibility(OrganizationEntity.HierarchyVisibility.NONE)
                .supporterEnabled(true)
                .build());

        teamRepository.save(TeamEntity.builder()
                .slug("sysadmin-resp-team-" + seq)
                .name("SYSADMINRESP チーム")
                .visibility(TeamEntity.Visibility.PUBLIC)
                .supporterEnabled(true)
                .build());
    }

    // ═══════════════════════════════════════════════════════════════════════
    // AC-1: users EP の PII / 内部フラグ非露出（許可リスト）
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @WithMockUser(username = "1", roles = "SYSTEM_ADMIN")
    @DisplayName("AC-1: users EP に禁止キーが出現せず、要素キーが許可リストの部分集合である")
    void ac1_usersEp_許可リスト遵守() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/system-admin/dashboard/users")
                        .param("size", "2000"))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode content = objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data").path("content");
        assertThat(content.isArray()).as("data.content は配列である").isTrue();

        // 全要素に禁止キーが一切出現しないこと
        for (JsonNode userNode : content) {
            Iterator<String> names = userNode.fieldNames();
            while (names.hasNext()) {
                String key = names.next();
                assertThat(FORBIDDEN_USER_KEYS)
                        .as("users EP レスポンス要素に禁止キー '%s' が出現しない", key)
                        .doesNotContain(key);
            }
        }

        // seed したユーザーを特定し、そのキー集合が許可リストの部分集合であること（許可リスト方式の照合）
        JsonNode mine = null;
        for (JsonNode userNode : content) {
            if (seededEmail.equals(userNode.path("email").asText())) {
                mine = userNode;
                break;
            }
        }
        assertThat(mine).as("seed したユーザーが users EP レスポンスに含まれる").isNotNull();

        Iterator<String> mineNames = mine.fieldNames();
        while (mineNames.hasNext()) {
            String key = mineNames.next();
            assertThat(ALLOWED_USER_KEYS)
                    .as("seed ユーザーのキー '%s' が許可リストに含まれる", key)
                    .contains(key);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // AC-2: Page 形の維持 + archivedAt
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @WithMockUser(username = "1", roles = "SYSTEM_ADMIN")
    @DisplayName("AC-2a: organizations EP は Page 形を維持し要素が archivedAt を持つ")
    void ac2_organizations_Page形とarchivedAt() throws Exception {
        JsonNode data = fetchData("/api/v1/system-admin/dashboard/organizations");
        assertPageShape(data);
        assertThat(data.path("content").get(0).has("archivedAt"))
                .as("organizations 要素は archivedAt キーを持つ")
                .isTrue();
    }

    @Test
    @WithMockUser(username = "1", roles = "SYSTEM_ADMIN")
    @DisplayName("AC-2b: teams EP は Page 形を維持し要素が archivedAt を持つ")
    void ac2_teams_Page形とarchivedAt() throws Exception {
        JsonNode data = fetchData("/api/v1/system-admin/dashboard/teams");
        assertPageShape(data);
        assertThat(data.path("content").get(0).has("archivedAt"))
                .as("teams 要素は archivedAt キーを持つ")
                .isTrue();
    }

    @Test
    @WithMockUser(username = "1", roles = "SYSTEM_ADMIN")
    @DisplayName("AC-2c: users EP は Page 形（content / totalElements / totalPages）を維持する")
    void ac2_users_Page形() throws Exception {
        JsonNode data = fetchData("/api/v1/system-admin/dashboard/users");
        assertPageShape(data);
    }

    private JsonNode fetchData(String url) throws Exception {
        MvcResult result = mockMvc.perform(get(url).param("size", "2000"))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
    }

    private void assertPageShape(JsonNode data) {
        assertThat(data.has("content")).as("data.content が存在する").isTrue();
        assertThat(data.path("content").isArray()).as("data.content は配列である").isTrue();
        assertThat(data.path("content").size()).as("シードした要素が含まれる").isGreaterThanOrEqualTo(1);
        assertThat(data.has("totalElements")).as("data.totalElements が存在する").isTrue();
        assertThat(data.has("totalPages")).as("data.totalPages が存在する").isTrue();
    }

    // ═══════════════════════════════════════════════════════════════════════
    // AC-11: 認可回帰
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("AC-11d: system-admin ダッシュボード EP は未認証で 401")
    void ac11_dashboardUnauthenticated_401() throws Exception {
        mockMvc.perform(get("/api/v1/system-admin/dashboard/users"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(username = "2", roles = "MEMBER")
    @DisplayName("AC-11e: system-admin ダッシュボード EP は SYSTEM_ADMIN でないユーザーで 403")
    void ac11_dashboardNonAdmin_403() throws Exception {
        mockMvc.perform(get("/api/v1/system-admin/dashboard/users"))
                .andExpect(status().isForbidden());
    }
}
