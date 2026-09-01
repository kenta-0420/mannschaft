package com.mannschaft.app.receipt;

import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.membership.entity.MembershipEntity;
import com.mannschaft.app.membership.repository.MembershipRepository;
import com.mannschaft.app.receipt.entity.ReceiptPresetEntity;
import com.mannschaft.app.receipt.repository.ReceiptPresetRepository;
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
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicInteger;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * receipt ドメイン（F08.4 領収書）の認可 API 契約テスト（認可根治戦役 Wave2 トランシェ2A・#1）。
 *
 * <h2>守るバグ</h2>
 * <p>receipt ドメイン全体で {@code AccessControlService} が一切注入されておらず、
 * 領収書プリセット・発行者設定・発行待ちキューの閲覧/変更 API に認可チェックが皆無だった。
 * 非メンバーが他チームの領収書プリセットを閲覧・作成でき、別スコープの ADMIN が
 * 正しいスコープIDさえ知っていれば他チームの領収書関連リソースを改変できる BOLA が存在した。
 * 本テストは Service 層に敷設した {@code checkMembership}/{@code checkAdminOrAbove}
 * （id 系操作は entity 由来スコープで検証）が効き、非メンバー/非ADMIN/別スコープADMIN が
 * 叩くと 403（COMMON_002）または（entity不一致時は）存在秘匿の 4xx になることを
 * 実 MySQL に対して検証する。</p>
 *
 * <h2>攻撃者と被害者スコープは別 ID（userID==teamID すり抜けの排除）</h2>
 * <ul>
 *   <li>ADMIN_A(920100001): teamA の ADMIN（正当な管理者）</li>
 *   <li>MEMBER_A(920100002): teamA の非 ADMIN メンバー（攻撃者）</li>
 *   <li>ADMIN_B(920100003): teamB の ADMIN（別スコープ管理者 = 越境攻撃者）</li>
 *   <li>OUTSIDER(920100099): teamA/teamB いずれにも属さない非メンバー</li>
 * </ul>
 */
@AutoConfigureMockMvc
@Transactional
@DisplayName("receipt 認可根治 API 契約テスト（Wave2 トランシェ2A・#1）")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class ReceiptAuthzContractTest extends AbstractMySqlIntegrationTest {

    private static final Long ADMIN_A = 920100001L;
    private static final Long MEMBER_A = 920100002L;
    private static final Long ADMIN_B = 920100003L;
    private static final Long OUTSIDER = 920100099L;

    @Autowired
    private MockMvc mockMvc;

    @PersistenceContext
    private EntityManager em;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private UserRoleRepository userRoleRepository;

    @Autowired
    private MembershipRepository membershipRepository;

    @Autowired
    private TeamRepository teamRepository;

    @Autowired
    private ReceiptPresetRepository presetRepository;

    private static final AtomicInteger SLUG_SEQ = new AtomicInteger(0);

    private Long adminRoleId;

    private Long teamAId;
    private Long teamBId;
    private Long presetAId;

    @BeforeEach
    void setUp() {
        MembershipTestHelper.insertActiveUser(em, ADMIN_A);
        MembershipTestHelper.insertActiveUser(em, MEMBER_A);
        MembershipTestHelper.insertActiveUser(em, ADMIN_B);
        MembershipTestHelper.insertActiveUser(em, OUTSIDER);

        // roles はグローバル参照テーブル（本番は V2.014 で seed）。共有 Testcontainer を汚さないため、
        // 削除・再INSERT せず name で既存を引く（無ければ idempotent に作成）。本クラスは @Transactional なので
        // 全 seed はテスト毎にロールバックされ、他テストと衝突しない。
        ensureRoles();

        TeamEntity teamA = saveTeam("領収書認可テストチームA");
        TeamEntity teamB = saveTeam("領収書認可テストチームB");
        teamAId = teamA.getId();
        teamBId = teamB.getId();

        // ADMIN_A: teamA の ADMIN
        saveTeamUserRole(ADMIN_A, teamAId, adminRoleId);
        // MEMBER_A: teamA の非 ADMIN メンバー（memberships 専属）
        saveMembership(MEMBER_A, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        // ADMIN_B: teamB のみの ADMIN（teamA には無関係 = 越境攻撃者）
        saveTeamUserRole(ADMIN_B, teamBId, adminRoleId);

        // teamA に属する領収書プリセット（BOLA 対象）
        ReceiptPresetEntity preset = presetRepository.save(ReceiptPresetEntity.builder()
                .scopeType(ReceiptScopeType.TEAM)
                .scopeId(teamAId)
                .name("月会費")
                .description("通常会費")
                .amount(new BigDecimal("5000"))
                .createdBy(ADMIN_A)
                .build());
        presetAId = preset.getId();
    }

    private void ensureRoles() {
        adminRoleId = ensureRole("ADMIN", 2);
        ensureRole("SYSTEM_ADMIN", 1);
        ensureRole("DEPUTY_ADMIN", 3);
        ensureRole("MEMBER", 4);
        ensureRole("SUPPORTER", 5);
        ensureRole("GUEST", 6);
    }

    /**
     * roles を name で引き、無ければ本番 V2.014 と同一 priority で作成して id を返す
     * （idempotent・グローバル参照テーブルを破壊しない）。
     */
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

    private TeamEntity saveTeam(String name) {
        return teamRepository.save(TeamEntity.builder()
                .slug("receipt-authz-" + SLUG_SEQ.incrementAndGet())
                .name(name)
                .visibility(TeamEntity.Visibility.MEMBERS_AND_ABOVE)
                .supporterEnabled(true)
                .build());
    }

    private void saveTeamUserRole(Long userId, Long teamId, Long roleId) {
        userRoleRepository.save(UserRoleEntity.builder()
                .userId(userId).roleId(roleId).teamId(teamId).build());
    }

    private void saveMembership(Long userId, ScopeType scopeType, Long scopeId, RoleKind roleKind) {
        membershipRepository.save(MembershipEntity.builder()
                .userId(userId).scopeType(scopeType).scopeId(scopeId).roleKind(roleKind)
                .joinedAt(LocalDateTime.now()).build());
    }

    // ───────────────────────────── AC-2-1: 閲覧系（listPresets） checkMembership ─────────────────────────────

    @Test
    @DisplayName("AC-2-1a: 未認証でプリセット一覧を叩くと 401")
    void listPresets_byUnauthenticated_unauthorized() throws Exception {
        // 401 は SecurityConfig(認証層)の認証エントリポイントの責務であり、
        // ドメイン認可(403 COMMON_002)とは別レイヤ。401 ボディにはアプリの
        // error エンベロープ($.error.code)が存在しないため、ステータスのみ検証する。
        mockMvc.perform(get("/api/v1/admin/receipt-presets")
                        .param("scopeType", "TEAM")
                        .param("scopeId", String.valueOf(teamAId)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(username = "920100099")
    @DisplayName("AC-2-1b: 非メンバーがプリセット一覧を叩くと 403")
    void listPresets_byNonMember_forbidden() throws Exception {
        mockMvc.perform(get("/api/v1/admin/receipt-presets")
                        .param("scopeType", "TEAM")
                        .param("scopeId", String.valueOf(teamAId)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("COMMON_002"));
    }

    @Test
    @WithMockUser(username = "920100002")
    @DisplayName("AC-2-1c(正常系): teamAの非ADMINメンバーはプリセット一覧を閲覧できる → 200")
    void listPresets_byMember_ok() throws Exception {
        mockMvc.perform(get("/api/v1/admin/receipt-presets")
                        .param("scopeType", "TEAM")
                        .param("scopeId", String.valueOf(teamAId)))
                .andExpect(status().isOk());
    }

    // ───────────────────────────── AC-2-2: 変更系（createPreset） checkAdminOrAbove ─────────────────────────────

    private String presetBody() {
        return "{\"name\":\"新規プリセット\",\"description\":\"テスト\",\"amount\":3000}";
    }

    @Test
    @WithMockUser(username = "920100002")
    @DisplayName("AC-2-2a: teamAの非ADMINメンバーがプリセットを作成 → 403")
    void createPreset_byNonAdminMember_forbidden() throws Exception {
        mockMvc.perform(post("/api/v1/admin/receipt-presets")
                        .param("scopeType", "TEAM")
                        .param("scopeId", String.valueOf(teamAId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(presetBody()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("COMMON_002"));
    }

    @Test
    @WithMockUser(username = "920100003")
    @DisplayName("AC-2-2b: 別スコープ(teamB)ADMINがteamAのプリセットを作成(BOLA) → 403")
    void createPreset_byCrossScopeAdmin_forbidden() throws Exception {
        mockMvc.perform(post("/api/v1/admin/receipt-presets")
                        .param("scopeType", "TEAM")
                        .param("scopeId", String.valueOf(teamAId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(presetBody()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("COMMON_002"));
    }

    @Test
    @WithMockUser(username = "920100001")
    @DisplayName("AC-2-2c(正常系): teamAのADMINはプリセットを作成できる → 201")
    void createPreset_byValidAdmin_ok() throws Exception {
        mockMvc.perform(post("/api/v1/admin/receipt-presets")
                        .param("scopeType", "TEAM")
                        .param("scopeId", String.valueOf(teamAId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(presetBody()))
                .andExpect(status().isCreated());
    }

    // ───────────────────────────── AC-2-3: entity由来スコープでのBOLA遮断（deletePreset） ─────────────────────────────

    @Test
    @WithMockUser(username = "920100003")
    @DisplayName("AC-2-3a(BOLA): teamBのADMINが自スコープ(teamB)を偽装してteamAのプリセットを削除 → 見つからず404相当")
    void deletePreset_byCrossScopeAdmin_withOwnScope_notFound() throws Exception {
        mockMvc.perform(delete("/api/v1/admin/receipt-presets/" + presetAId)
                        .param("scopeType", "TEAM")
                        .param("scopeId", String.valueOf(teamBId)))
                .andExpect(jsonPath("$.error.code").value("RECEIPT_003"));
    }

    @Test
    @WithMockUser(username = "920100003")
    @DisplayName("AC-2-3b(BOLA): teamBのADMINがteamAの正しいscopeIdを指定してもentity由来認可で403")
    void deletePreset_byCrossScopeAdmin_withVictimScope_forbidden() throws Exception {
        mockMvc.perform(delete("/api/v1/admin/receipt-presets/" + presetAId)
                        .param("scopeType", "TEAM")
                        .param("scopeId", String.valueOf(teamAId)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("COMMON_002"));
    }

    @Test
    @WithMockUser(username = "920100001")
    @DisplayName("AC-2-3c(正常系): teamAのADMINは自チームのプリセットを削除できる → 204")
    void deletePreset_byValidAdmin_ok() throws Exception {
        mockMvc.perform(delete("/api/v1/admin/receipt-presets/" + presetAId)
                        .param("scopeType", "TEAM")
                        .param("scopeId", String.valueOf(teamAId)))
                .andExpect(status().isNoContent());
    }

    // ───────────────────────────── AC-2-4: 発行者設定変更（upsertSettings） checkAdminOrAbove ─────────────────────────────

    private String issuerSettingsBody() {
        return "{\"issuerName\":\"テスト組織\",\"isQualifiedInvoicer\":false}";
    }

    @Test
    @WithMockUser(username = "920100002")
    @DisplayName("AC-2-4a: teamAの非ADMINメンバーが発行者設定を変更 → 403")
    void upsertSettings_byNonAdminMember_forbidden() throws Exception {
        mockMvc.perform(put("/api/v1/admin/receipt-settings")
                        .param("scopeType", "TEAM")
                        .param("scopeId", String.valueOf(teamAId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(issuerSettingsBody()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("COMMON_002"));
    }

    @Test
    @WithMockUser(username = "920100001")
    @DisplayName("AC-2-4b(正常系): teamAのADMINは発行者設定を変更できる → 200")
    void upsertSettings_byValidAdmin_ok() throws Exception {
        mockMvc.perform(put("/api/v1/admin/receipt-settings")
                        .param("scopeType", "TEAM")
                        .param("scopeId", String.valueOf(teamAId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(issuerSettingsBody()))
                .andExpect(status().isOk());
    }

    // ───────────────────────────── AC-2-5: 発行待ちキュー閲覧（listQueue） checkAdminOrAbove ─────────────────────────────

    @Test
    @WithMockUser(username = "920100002")
    @DisplayName("AC-2-5: teamAの非ADMINメンバーが発行待ちキュー一覧を閲覧 → 403")
    void listQueue_byNonAdminMember_forbidden() throws Exception {
        mockMvc.perform(get("/api/v1/admin/receipt-queue")
                        .param("scopeType", "TEAM")
                        .param("scopeId", String.valueOf(teamAId)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("COMMON_002"));
    }
}
