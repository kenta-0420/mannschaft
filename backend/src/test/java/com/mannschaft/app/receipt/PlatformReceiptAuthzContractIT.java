package com.mannschaft.app.receipt;

import com.mannschaft.app.role.entity.RoleEntity;
import com.mannschaft.app.role.entity.UserRoleEntity;
import com.mannschaft.app.role.repository.RoleRepository;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.MembershipTestHelper;
import com.mannschaft.app.team.entity.TeamEntity;
import com.mannschaft.app.team.repository.TeamRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.concurrent.atomic.AtomicInteger;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F08.12 §2.1「認可の前提」の試練（red）。運営コンソール（PLATFORM スコープ）の
 * 領収書 API が SYSTEM_ADMIN 専用であり、団体 ADMIN が触れないことを
 * <strong>実 Security フィルタチェーンを通して</strong>検証する。
 *
 * <p>{@code AccessControlService} はモックしない。認可の抜けは純 Mockito UT では
 * 構造的に検出できないため、{@code @SpringBootTest} + 実フィルタ + MockMvc で観測する。
 *
 * <p>対応する受け入れ条件: AC-01 / AC-02 / AC-03 / AC-27 / AC-34。
 *
 * <p><b>red である理由</b>: {@code /api/v1/system-admin/receipt-settings} および
 * {@code /api/v1/system-admin/receipts} は未実装であり、現状は 404 になる。
 * とりわけ AC-02 は「403 であること。500 にならないこと」を求めており、
 * 認可の穴と実装漏れの両方をここで一度に塞ぐ。
 */
@AutoConfigureMockMvc
@Transactional
@DisplayName("F08.12 運営領収書 API の認可契約テスト（試練・実装前 red）")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class PlatformReceiptAuthzContractIT extends AbstractMySqlIntegrationTest {

    private static final String SETTINGS_PATH = "/api/v1/system-admin/receipt-settings";
    private static final String RECEIPTS_PATH = "/api/v1/system-admin/receipts";
    private static final String TEAM_RECEIPTS_PATH = "/api/v1/admin/receipts";

    private static final Long SYSTEM_ADMIN_USER = 920812101L;
    private static final Long TEAM_ADMIN_USER = 920812102L;
    private static final Long MEMBER_USER = 920812103L;

    private static final AtomicInteger SLUG_SEQ = new AtomicInteger(0);

    @Autowired private MockMvc mockMvc;
    @Autowired private RoleRepository roleRepository;
    @Autowired private UserRoleRepository userRoleRepository;
    @Autowired private TeamRepository teamRepository;

    @PersistenceContext private EntityManager em;

    private Long teamId;

    @BeforeEach
    void setUp() {
        MembershipTestHelper.insertActiveUser(em, SYSTEM_ADMIN_USER);
        MembershipTestHelper.insertActiveUser(em, TEAM_ADMIN_USER);
        MembershipTestHelper.insertActiveUser(em, MEMBER_USER);

        Long systemAdminRoleId = ensureRole("SYSTEM_ADMIN", 1);
        Long adminRoleId = ensureRole("ADMIN", 2);
        Long memberRoleId = ensureRole("MEMBER", 4);

        teamId = teamRepository.save(TeamEntity.builder()
                .slug("platform-receipt-authz-" + SLUG_SEQ.incrementAndGet())
                .name("運営領収書認可テストチーム")
                .visibility(TeamEntity.Visibility.MEMBERS_AND_ABOVE)
                .supporterEnabled(true)
                .build()).getId();

        // SYSTEM_ADMIN は PLATFORM スコープ（team_id なし）で付与される
        userRoleRepository.save(UserRoleEntity.builder()
                .userId(SYSTEM_ADMIN_USER).roleId(systemAdminRoleId).build());
        userRoleRepository.save(UserRoleEntity.builder()
                .userId(TEAM_ADMIN_USER).roleId(adminRoleId).teamId(teamId).build());
        userRoleRepository.save(UserRoleEntity.builder()
                .userId(MEMBER_USER).roleId(memberRoleId).teamId(teamId).build());

        em.flush();
        em.clear();
    }

    private Long ensureRole(String name, int priority) {
        return roleRepository.findByName(name)
                .map(RoleEntity::getId)
                .orElseGet(() -> roleRepository.save(RoleEntity.builder()
                        .name(name)
                        .displayName(name)
                        .priority(priority)
                        .isSystem("SYSTEM_ADMIN".equals(name))
                        .build()).getId());
    }

    @Test
    @WithMockUser(username = "920812101")
    @DisplayName("AC-01: SYSTEM_ADMIN は PLATFORM の発行者設定を取得できる（scopeType=PLATFORM, scopeId=0）")
    void ac01_systemAdminCanReadPlatformIssuerSettings() throws Exception {
        mockMvc.perform(get(SETTINGS_PATH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.scopeType").value("PLATFORM"))
                .andExpect(jsonPath("$.data.scopeId").value(0));
    }

    @Test
    @WithMockUser(username = "920812102")
    @DisplayName("AC-02: 団体 ADMIN が運営領収書一覧を呼ぶと 403（500 にならない）")
    void ac02_teamAdminIsForbiddenFromPlatformReceipts() throws Exception {
        mockMvc.perform(get(RECEIPTS_PATH))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "920812102")
    @DisplayName("AC-02(設定): 団体 ADMIN が運営の発行者設定を呼んでも 403")
    void ac02_teamAdminIsForbiddenFromPlatformIssuerSettings() throws Exception {
        mockMvc.perform(get(SETTINGS_PATH))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("AC-03: 未認証は 401")
    void ac03_unauthenticatedIsUnauthorized() throws Exception {
        mockMvc.perform(get(RECEIPTS_PATH))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(username = "920812101")
    @DisplayName("AC-34: 領収書が 0 件でも 200 + 空配列（404 にしない）")
    void ac34_emptyListReturnsOkWithEmptyArray() throws Exception {
        mockMvc.perform(get(RECEIPTS_PATH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content").isArray());
    }

    @Test
    @WithMockUser(username = "920812102")
    @DisplayName("AC-27: 団体 ADMIN が PLATFORM の領収書 ID を団体側 API に渡すと 404（存在オラクルを消す）")
    void ac27_platformReceiptIdIsNotFoundOnTeamApi() throws Exception {
        // PLATFORM スコープの領収書 ID は団体側 API の findReceiptOrThrow で
        // scope_type が一致しないため必ず 404 になること。
        // 実装前は当該 ID を作る経路自体が無いため、存在しない ID で契約だけを固定する。
        mockMvc.perform(get(TEAM_RECEIPTS_PATH + "/999999999")
                        .param("scopeType", "TEAM")
                        .param("scopeId", String.valueOf(teamId)))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(username = "920812103")
    @DisplayName("AC-26: 組織の MEMBER は領収書一覧を取得できない（ADMIN 以上のみ）")
    void ac26_memberIsForbiddenFromReceiptList() throws Exception {
        mockMvc.perform(get(TEAM_RECEIPTS_PATH)
                        .param("scopeType", "TEAM")
                        .param("scopeId", String.valueOf(teamId)))
                .andExpect(status().isForbidden());
    }
}
