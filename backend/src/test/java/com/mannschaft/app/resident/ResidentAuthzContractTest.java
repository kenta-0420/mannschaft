package com.mannschaft.app.resident;

import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.membership.entity.MembershipEntity;
import com.mannschaft.app.membership.repository.MembershipRepository;
import com.mannschaft.app.resident.entity.DwellingUnitEntity;
import com.mannschaft.app.resident.entity.PropertyListingEntity;
import com.mannschaft.app.resident.entity.ResidentDocumentEntity;
import com.mannschaft.app.resident.entity.ResidentRegistryEntity;
import com.mannschaft.app.resident.repository.DwellingUnitRepository;
import com.mannschaft.app.resident.repository.PropertyListingRepository;
import com.mannschaft.app.resident.repository.ResidentDocumentRepository;
import com.mannschaft.app.resident.repository.ResidentRegistryRepository;
import com.mannschaft.app.role.entity.RoleEntity;
import com.mannschaft.app.role.entity.UserRoleEntity;
import com.mannschaft.app.role.repository.RoleRepository;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.team.entity.TeamEntity;
import com.mannschaft.app.team.repository.TeamRepository;
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

import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicInteger;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * resident ドメイン（F09.1 居住者台帳・最機密PII）の認可 API 契約テスト（認可根治戦役 Wave2 トランシェ2A・#2）。
 *
 * <h2>守るバグ</h2>
 * <p>resident ドメイン全体で {@code AccessControlService} が一切注入されておらず、居室・居住者・
 * 本人確認書類・物件掲示板の閲覧/変更 API に認可チェックが皆無だった。特に
 * {@code ResidentRegistryService.update/delete/verify/moveOut} と {@code ResidentDocumentService} 全メソッドは
 * path の teamId/orgId すら参照せず raw ID で直接操作しており、正当な ADMIN 権限を持つユーザーが
 * 別スコープの居住者・本人確認書類を自由に閲覧・改変・物理削除できる BOLA が存在した。</p>
 *
 * <p>本テストは Service 層に敷設した {@code checkMembership}/{@code checkAdminOrAbove}
 * （居住者/書類は「居住者→居室」を辿った entity 由来スコープで検証。path の teamId/orgId は信用しない）が効き、
 * 非メンバー/非ADMIN/別スコープADMIN が叩くと 403（COMMON_002）になることを実 MySQL に対して検証する。</p>
 *
 * <h2>攻撃者と被害者スコープは別 ID（userID==teamID すり抜けの排除）</h2>
 * <ul>
 *   <li>ADMIN_A(923000001): teamA の ADMIN（正当な管理者・被害者側の管理者）</li>
 *   <li>MEMBER_A(923000002): teamA の非 ADMIN メンバー（攻撃者）</li>
 *   <li>ADMIN_B(923000003): teamB の ADMIN（別スコープ管理者 = 越境攻撃者）</li>
 *   <li>OUTSIDER(923000099): teamA/teamB いずれにも属さない非メンバー</li>
 * </ul>
 */
@AutoConfigureMockMvc
@Transactional
@DisplayName("resident 認可根治 API 契約テスト（Wave2 トランシェ2A・#2）")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class ResidentAuthzContractTest extends AbstractMySqlIntegrationTest {

    private static final Long ADMIN_A = 923000001L;
    private static final Long MEMBER_A = 923000002L;
    private static final Long ADMIN_B = 923000003L;
    private static final Long OUTSIDER = 923000099L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private UserRoleRepository userRoleRepository;

    @Autowired
    private MembershipRepository membershipRepository;

    @Autowired
    private TeamRepository teamRepository;

    @Autowired
    private DwellingUnitRepository dwellingUnitRepository;

    @Autowired
    private ResidentRegistryRepository residentRepository;

    @Autowired
    private ResidentDocumentRepository documentRepository;

    @Autowired
    private PropertyListingRepository listingRepository;

    private static final AtomicInteger SLUG_SEQ = new AtomicInteger(0);

    private Long adminRoleId;

    private Long teamAId;
    private Long teamBId;
    private Long unitAId;
    private Long residentAId;
    private Long docAId;
    private Long listingAId;

    @BeforeEach
    void setUp() {
        // roles はグローバル参照テーブル（本番は V2.014 で seed）。共有 Testcontainer を汚さないため、
        // 削除・再INSERT せず name で既存を引く（無ければ idempotent に作成）。本クラスは @Transactional なので
        // 全 seed はテスト毎にロールバックされ、他テストと衝突しない。
        ensureRoles();

        TeamEntity teamA = saveTeam("F091認可テストチームA");
        TeamEntity teamB = saveTeam("F091認可テストチームB");
        teamAId = teamA.getId();
        teamBId = teamB.getId();

        // ADMIN_A: teamA の ADMIN（被害者側の正当な管理者）
        saveTeamUserRole(ADMIN_A, teamAId, adminRoleId);
        // MEMBER_A: teamA の非 ADMIN メンバー（memberships 専属・攻撃者）
        saveMembership(MEMBER_A, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        // ADMIN_B: teamB のみの ADMIN（teamA には無関係 = 越境攻撃者）
        saveTeamUserRole(ADMIN_B, teamBId, adminRoleId);

        // teamA に属する居室・居住者・書類・物件掲示（BOLA 対象）
        DwellingUnitEntity unitA = dwellingUnitRepository.save(DwellingUnitEntity.builder()
                .scopeType("TEAM").teamId(teamAId).unitNumber("101").build());
        unitAId = unitA.getId();

        ResidentRegistryEntity residentA = residentRepository.save(ResidentRegistryEntity.builder()
                .dwellingUnitId(unitAId)
                .residentType("OWNER")
                .lastName("佐藤")
                .firstName("花子")
                .moveInDate(java.time.LocalDate.now())
                .build());
        residentAId = residentA.getId();

        ResidentDocumentEntity docA = documentRepository.save(ResidentDocumentEntity.builder()
                .residentId(residentAId)
                .documentType("ID_CARD")
                .fileName("license.pdf")
                .s3Key("docs/license.pdf")
                .fileSize(2048)
                .contentType("application/pdf")
                .uploadedBy(ADMIN_A)
                .build());
        docAId = docA.getId();

        PropertyListingEntity listingA = listingRepository.save(PropertyListingEntity.builder()
                .dwellingUnitId(unitAId)
                .listedBy(ADMIN_A)
                .listingType("SALE")
                .title("テスト物件A")
                .build());
        listingAId = listingA.getId();
    }

    private void ensureRoles() {
        adminRoleId = ensureRole("ADMIN", 2);
        ensureRole("SYSTEM_ADMIN", 1);
        ensureRole("DEPUTY_ADMIN", 3);
        ensureRole("MEMBER", 4);
        ensureRole("SUPPORTER", 5);
        ensureRole("GUEST", 6);
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

    private TeamEntity saveTeam(String name) {
        return teamRepository.save(TeamEntity.builder()
                .slug("resident-authz-" + SLUG_SEQ.incrementAndGet())
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

    // ───────────────────────────── AC-R1: 居室一覧（listByTeam） checkMembership ─────────────────────────────

    @Test
    @WithMockUser(username = "923000099")
    @DisplayName("AC-R1a: 非メンバーが居室一覧を叩くと 403")
    void listDwellingUnits_byNonMember_forbidden() throws Exception {
        mockMvc.perform(get("/api/v1/teams/{teamId}/dwelling-units", teamAId))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("COMMON_002"));
    }

    @Test
    @WithMockUser(username = "923000002")
    @DisplayName("AC-R1b(正常系): teamAの非ADMINメンバーは居室一覧を閲覧できる → 200")
    void listDwellingUnits_byMember_ok() throws Exception {
        mockMvc.perform(get("/api/v1/teams/{teamId}/dwelling-units", teamAId))
                .andExpect(status().isOk());
    }

    // ───────────────────────────── AC-R2: 居室作成（createForTeam） checkAdminOrAbove ─────────────────────────────

    @Test
    @WithMockUser(username = "923000002")
    @DisplayName("AC-R2a: teamAの非ADMINメンバーが居室を作成 → 403")
    void createDwellingUnit_byNonAdminMember_forbidden() throws Exception {
        mockMvc.perform(post("/api/v1/teams/{teamId}/dwelling-units", teamAId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"unitNumber\":\"201\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("COMMON_002"));
    }

    @Test
    @WithMockUser(username = "923000003")
    @DisplayName("AC-R2b: 別チーム(teamB)ADMINがteamAの居室を作成 → 403")
    void createDwellingUnit_byCrossScopeAdmin_forbidden() throws Exception {
        mockMvc.perform(post("/api/v1/teams/{teamId}/dwelling-units", teamAId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"unitNumber\":\"201\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("COMMON_002"));
    }

    @Test
    @WithMockUser(username = "923000001")
    @DisplayName("AC-R2c(正常系): teamAのADMINは居室を作成できる → 201")
    void createDwellingUnit_byValidAdmin_ok() throws Exception {
        mockMvc.perform(post("/api/v1/teams/{teamId}/dwelling-units", teamAId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"unitNumber\":\"201\"}"))
                .andExpect(status().isCreated());
    }

    // ───────────────────────────── AC-R3: 居住者一覧（listByUnit） checkMembership（居室由来スコープ） ─────────────────────────────

    @Test
    @WithMockUser(username = "923000099")
    @DisplayName("AC-R3a: 非メンバーが居住者一覧を叩くと 403")
    void listResidents_byNonMember_forbidden() throws Exception {
        mockMvc.perform(get("/api/v1/teams/{teamId}/dwelling-units/{unitId}/residents", teamAId, unitAId))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("COMMON_002"));
    }

    @Test
    @WithMockUser(username = "923000002")
    @DisplayName("AC-R3b(正常系): teamAの非ADMINメンバーは居住者一覧を閲覧できる → 200")
    void listResidents_byMember_ok() throws Exception {
        mockMvc.perform(get("/api/v1/teams/{teamId}/dwelling-units/{unitId}/residents", teamAId, unitAId))
                .andExpect(status().isOk());
    }

    // ───────────────────────────── AC-R4: 居住者登録（create） checkAdminOrAbove（居室由来スコープ） ─────────────────────────────

    private String residentBody() {
        return "{\"residentType\":\"TENANT\",\"lastName\":\"鈴木\",\"firstName\":\"次郎\",\"moveInDate\":\"2026-01-01\"}";
    }

    @Test
    @WithMockUser(username = "923000002")
    @DisplayName("AC-R4a: teamAの非ADMINメンバーが居住者を登録 → 403")
    void createResident_byNonAdminMember_forbidden() throws Exception {
        mockMvc.perform(post("/api/v1/teams/{teamId}/dwelling-units/{unitId}/residents", teamAId, unitAId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(residentBody()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("COMMON_002"));
    }

    @Test
    @WithMockUser(username = "923000001")
    @DisplayName("AC-R4b(正常系): teamAのADMINは居住者を登録できる → 201")
    void createResident_byValidAdmin_ok() throws Exception {
        mockMvc.perform(post("/api/v1/teams/{teamId}/dwelling-units/{unitId}/residents", teamAId, unitAId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(residentBody()))
                .andExpect(status().isCreated());
    }

    // ───────────────────────────── AC-R5: 居住者更新/削除/確認/退去（BOLA: entity由来スコープ） ─────────────────────────────

    @Test
    @WithMockUser(username = "923000003")
    @DisplayName("AC-R5a(BOLA): teamBのADMINが自スコープ(teamB)のURLでteamAの居住者を更新 → 403")
    void updateResident_byCrossScopeAdmin_forbidden() throws Exception {
        mockMvc.perform(put("/api/v1/teams/{teamId}/residents/{id}", teamBId, residentAId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(residentBody()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("COMMON_002"));
    }

    @Test
    @WithMockUser(username = "923000001")
    @DisplayName("AC-R5b(正常系): teamAのADMINは自チームの居住者を更新できる → 200")
    void updateResident_byValidAdmin_ok() throws Exception {
        mockMvc.perform(put("/api/v1/teams/{teamId}/residents/{id}", teamAId, residentAId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(residentBody()))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = "923000002")
    @DisplayName("AC-R5c: teamAの非ADMINメンバーが居住者を確認 → 403")
    void verifyResident_byNonAdminMember_forbidden() throws Exception {
        mockMvc.perform(patch("/api/v1/teams/{teamId}/residents/{id}/verify", teamAId, residentAId))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("COMMON_002"));
    }

    @Test
    @WithMockUser(username = "923000001")
    @DisplayName("AC-R5d(正常系): teamAのADMINは居住者を確認できる → 200")
    void verifyResident_byValidAdmin_ok() throws Exception {
        mockMvc.perform(patch("/api/v1/teams/{teamId}/residents/{id}/verify", teamAId, residentAId))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = "923000003")
    @DisplayName("AC-R5e(BOLA): teamBのADMINが自スコープ(teamB)のURLでteamAの居住者を退去処理 → 403")
    void moveOutResident_byCrossScopeAdmin_forbidden() throws Exception {
        mockMvc.perform(patch("/api/v1/teams/{teamId}/residents/{id}/move-out", teamBId, residentAId))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("COMMON_002"));
    }

    @Test
    @WithMockUser(username = "923000003")
    @DisplayName("AC-R5f(BOLA): teamBのADMINが自スコープ(teamB)のURLでteamAの居住者を削除 → 403")
    void deleteResident_byCrossScopeAdmin_forbidden() throws Exception {
        mockMvc.perform(delete("/api/v1/teams/{teamId}/residents/{id}", teamBId, residentAId))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("COMMON_002"));
    }

    @Test
    @WithMockUser(username = "923000001")
    @DisplayName("AC-R5g(正常系): teamAのADMINは自チームの居住者を削除できる → 204")
    void deleteResident_byValidAdmin_ok() throws Exception {
        mockMvc.perform(delete("/api/v1/teams/{teamId}/residents/{id}", teamAId, residentAId))
                .andExpect(status().isNoContent());
    }

    // ───────────────────────────── AC-R6: 本人確認書類（最機密PII） ─────────────────────────────

    @Test
    @WithMockUser(username = "923000099")
    @DisplayName("AC-R6a: 非メンバーが書類一覧を閲覧 → 403")
    void listDocuments_byNonMember_forbidden() throws Exception {
        mockMvc.perform(get("/api/v1/teams/{teamId}/residents/{residentId}/documents", teamAId, residentAId))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("COMMON_002"));
    }

    @Test
    @WithMockUser(username = "923000002")
    @DisplayName("AC-R6b(正常系): teamAの非ADMINメンバーは書類一覧を閲覧できる → 200")
    void listDocuments_byMember_ok() throws Exception {
        mockMvc.perform(get("/api/v1/teams/{teamId}/residents/{residentId}/documents", teamAId, residentAId))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = "923000002")
    @DisplayName("AC-R6c: teamAの非ADMINメンバーが本人確認書類を追加 → 403")
    void uploadDocument_byNonAdminMember_forbidden() throws Exception {
        mockMvc.perform(post("/api/v1/teams/{teamId}/residents/{residentId}/documents", teamAId, residentAId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"documentType\":\"ID_CARD\",\"fileName\":\"a.pdf\",\"s3Key\":\"docs/a.pdf\","
                                + "\"fileSize\":100,\"contentType\":\"application/pdf\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("COMMON_002"));
    }

    @Test
    @WithMockUser(username = "923000001")
    @DisplayName("AC-R6d(正常系): teamAのADMINは本人確認書類を追加できる → 201")
    void uploadDocument_byValidAdmin_ok() throws Exception {
        mockMvc.perform(post("/api/v1/teams/{teamId}/residents/{residentId}/documents", teamAId, residentAId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"documentType\":\"ID_CARD\",\"fileName\":\"a.pdf\",\"s3Key\":\"docs/a.pdf\","
                                + "\"fileSize\":100,\"contentType\":\"application/pdf\"}"))
                .andExpect(status().isCreated());
    }

    @Test
    @WithMockUser(username = "923000003")
    @DisplayName("AC-R6e(BOLA・最機密PII): teamBのADMINが自スコープ(teamB)のURLでteamAの本人確認書類を物理削除 → 403")
    void deleteDocument_byCrossScopeAdmin_forbidden() throws Exception {
        mockMvc.perform(delete("/api/v1/teams/{teamId}/residents/{residentId}/documents/{docId}",
                        teamBId, residentAId, docAId))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("COMMON_002"));
    }

    @Test
    @WithMockUser(username = "923000001")
    @DisplayName("AC-R6f(正常系): teamAのADMINは自チームの本人確認書類を削除できる → 204")
    void deleteDocument_byValidAdmin_ok() throws Exception {
        mockMvc.perform(delete("/api/v1/teams/{teamId}/residents/{residentId}/documents/{docId}",
                        teamAId, residentAId, docAId))
                .andExpect(status().isNoContent());
    }

    // ───────────────────────────── AC-R7: 物件掲示板 ─────────────────────────────

    @Test
    @WithMockUser(username = "923000099")
    @DisplayName("AC-R7a: 非メンバーが物件一覧を閲覧 → 403")
    void listListings_byNonMember_forbidden() throws Exception {
        mockMvc.perform(get("/api/v1/teams/{teamId}/property-listings", teamAId))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("COMMON_002"));
    }

    @Test
    @WithMockUser(username = "923000002")
    @DisplayName("AC-R7b(正常系): teamAの非ADMINメンバーは物件一覧を閲覧できる → 200")
    void listListings_byMember_ok() throws Exception {
        mockMvc.perform(get("/api/v1/teams/{teamId}/property-listings", teamAId))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = "923000002")
    @DisplayName("AC-R7c: teamAの非ADMINメンバーが物件掲示を作成 → 403")
    void createListing_byNonAdminMember_forbidden() throws Exception {
        mockMvc.perform(post("/api/v1/teams/{teamId}/property-listings", teamAId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dwellingUnitId\":" + unitAId + ",\"listingType\":\"SALE\",\"title\":\"新規物件\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("COMMON_002"));
    }

    @Test
    @WithMockUser(username = "923000001")
    @DisplayName("AC-R7d(正常系): teamAのADMINは物件掲示を作成できる → 201")
    void createListing_byValidAdmin_ok() throws Exception {
        mockMvc.perform(post("/api/v1/teams/{teamId}/property-listings", teamAId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dwellingUnitId\":" + unitAId + ",\"listingType\":\"SALE\",\"title\":\"新規物件\"}"))
                .andExpect(status().isCreated());
    }

    @Test
    @WithMockUser(username = "923000003")
    @DisplayName("AC-R7e(BOLA): teamBのADMINが自スコープ(teamB)のURLでteamAの物件問い合わせ一覧を閲覧 → 403")
    void listInquiries_byCrossScopeAdmin_forbidden() throws Exception {
        mockMvc.perform(get("/api/v1/teams/{teamId}/property-listings/{id}/inquiries", teamBId, listingAId))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("COMMON_002"));
    }
}
