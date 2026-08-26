package com.mannschaft.app.member;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.member.entity.MemberProfileEntity;
import com.mannschaft.app.member.entity.MemberProfileFieldEntity;
import com.mannschaft.app.member.entity.TeamPageEntity;
import com.mannschaft.app.member.entity.TeamPageSectionEntity;
import com.mannschaft.app.member.repository.MemberProfileFieldRepository;
import com.mannschaft.app.member.repository.MemberProfileRepository;
import com.mannschaft.app.member.repository.TeamPageRepository;
import com.mannschaft.app.member.repository.TeamPageSectionRepository;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 認可根治戦役 Wave3-B2 — member ドメイン（TeamPage/MemberProfile/Section/Field）API 契約テスト（試練）。
 *
 * <p>正本: 早馬（殿からの直接指示）。member ドメイン全体に {@code AccessControlService} 呼び出しが
 * 皆無だった（TeamPageController/MemberProfileController/TeamPageSectionController/
 * MemberProfileFieldController の全 EP が未認可）。金型: {@code EquipmentScopeContractIT}
 * （{@code @AutoConfigureMockMvc(addFilters=false)} + 実 MySQL + 手動 SecurityContext）。</p>
 *
 * <p>認可モデル（member ドメイン固有の設計判断）:</p>
 * <ul>
 *   <li><b>スコープが呼び出し元から明示的に宣言される EP</b>（listPages/listFields の teamId
 *       query param、createPage/createField の body teamId）: {@code checkMembership}/
 *       {@code checkAdminOrAbove} で 403（COMMON_002）。スコープ自体は秘匿不要（呼び出し元が
 *       既に teamId を知っている）。</li>
 *   <li><b>bare id のみで対象を特定する EP</b>（pageId/sectionId/profileId/fieldId 直指定。
 *       URL に teamId/organizationId を含まない）: entity を fetch → entity 由来スコープで
 *       {@code isMember}/{@code isAdminOrAbove} を判定し、失敗時は 404（{@code MEMBER_00X}）で
 *       存在秘匿する（workflow ドメイン {@code WorkflowApprovalService#decide} 踏襲）。
 *       非所属の「完全な部外者」も「別チームの正規 ADMIN による越境」も、対象スコープに対する
 *       権限が無い点は区別不能なため、同一の 404 に収束する（これにより ID の存在有無を
 *       攻撃者に開示しない）。</li>
 * </ul>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("member ドメイン（TeamPage/プロフィール/セクション/フィールド）認可契約テスト（Wave3-B2 試練）")
class MemberScopeContractIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private TeamPageRepository pageRepository;

    @Autowired
    private TeamPageSectionRepository sectionRepository;

    @Autowired
    private MemberProfileRepository profileRepository;

    @Autowired
    private MemberProfileFieldRepository fieldRepository;

    @PersistenceContext
    private EntityManager em;

    private Long teamAId;
    private Long teamBId;

    private Long adminAId;   // teamA の ADMIN（正当）
    private Long adminBId;   // teamB の ADMIN（別 scope の越境攻撃者）
    private Long memberAId;  // teamA の非 ADMIN メンバー
    private Long outsiderId; // どこにも所属しない非メンバー

    private Long pageAId;    // teamA の MAIN ページ
    private Long pageA2Id;   // teamA の YEARLY ページ（copyMembers のコピー先用）
    private Long pageBId;    // teamB の MAIN ページ

    private Long sectionAId; // pageA 配下のセクション
    private Long profileAId; // pageA 配下のプロフィール
    private Long profileBId; // pageB 配下のプロフィール（reorder 越境書き込みテスト用）
    private Long fieldAId;   // teamA のフィールド定義

    @BeforeEach
    void setUp() {
        teamAId = insertTeam("MBAUTHZ チームA");
        teamBId = insertTeam("MBAUTHZ チームB");

        adminAId = insertUser("mbauthz-admin-a@example.com");
        adminBId = insertUser("mbauthz-admin-b@example.com");
        memberAId = insertUser("mbauthz-member-a@example.com");
        outsiderId = insertUser("mbauthz-outsider@example.com");

        // checkAdminOrAbove（user_roles）と checkMembership/isMember（memberships）は別系統のため
        // ADMIN ユーザーにも memberships 行を張る（Wave 踏襲の既知の地雷）。
        MembershipTestHelper.insertMembership(em, adminAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminAId, "ADMIN", teamAId, null);
        MembershipTestHelper.insertMembership(em, adminBId, ScopeType.TEAM, teamBId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminBId, "ADMIN", teamBId, null);
        MembershipTestHelper.insertMembership(em, memberAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        // outsiderId はどこにも所属させない。

        TeamPageEntity pageA = pageRepository.save(TeamPageEntity.builder()
                .teamId(teamAId).title("MBAUTHZ チームAメイン").slug("mbauthz-a-" + System.nanoTime())
                .pageType(PageType.MAIN).build());
        pageAId = pageA.getId();

        TeamPageEntity pageA2 = pageRepository.save(TeamPageEntity.builder()
                .teamId(teamAId).title("MBAUTHZ チームA年度").slug("mbauthz-a2-" + System.nanoTime())
                .pageType(PageType.YEARLY).year((short) 2024).build());
        pageA2Id = pageA2.getId();

        TeamPageEntity pageB = pageRepository.save(TeamPageEntity.builder()
                .teamId(teamBId).title("MBAUTHZ チームBメイン").slug("mbauthz-b-" + System.nanoTime())
                .pageType(PageType.MAIN).build());
        pageBId = pageB.getId();

        TeamPageSectionEntity sectionA = sectionRepository.save(TeamPageSectionEntity.builder()
                .teamPageId(pageAId).sectionType(SectionType.HEADING).title("MBAUTHZ 見出し").build());
        sectionAId = sectionA.getId();

        MemberProfileEntity profileA = profileRepository.save(MemberProfileEntity.builder()
                .teamPageId(pageAId).displayName("MBAUTHZ 選手A").build());
        profileAId = profileA.getId();

        MemberProfileEntity profileB = profileRepository.save(MemberProfileEntity.builder()
                .teamPageId(pageBId).displayName("MBAUTHZ 選手B").build());
        profileBId = profileB.getId();

        MemberProfileFieldEntity fieldA = fieldRepository.save(MemberProfileFieldEntity.builder()
                .teamId(teamAId).fieldName("MBAUTHZ ポジション").build());
        fieldAId = fieldA.getId();

        em.flush();
        em.clear();
    }

    // ═════════════════════════════════════════════════════════════════════
    // 1. GET /api/v1/team/pages（一覧・スコープ宣言型: checkMembership）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("1. GET /api/v1/team/pages（一覧）")
    class ListPages {

        @Test
        @DisplayName("非メンバーは403")
        void 非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get("/api/v1/team/pages").param("teamId", teamAId.toString()))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別チームADMIN（越境）は403")
        void 別チームADMINは403() throws Exception {
            setAuth(adminBId);
            mockMvc.perform(get("/api/v1/team/pages").param("teamId", teamAId.toString()))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当メンバーは200")
        void 正当メンバーは200() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(get("/api/v1/team/pages").param("teamId", teamAId.toString()))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 2. POST /api/v1/team/pages（作成・スコープ宣言型: checkAdminOrAbove）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("2. POST /api/v1/team/pages（作成）")
    class CreatePage {

        @Test
        @DisplayName("非ADMINメンバーは403")
        void 非ADMINメンバーは403() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(post("/api/v1/team/pages")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createPageBody())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別チームADMIN（bodyでteamAを指定・越境）は403")
        void 別チームADMINは403() throws Exception {
            setAuth(adminBId);
            mockMvc.perform(post("/api/v1/team/pages")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createPageBody())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINは201")
        void 正当ADMINは201() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(post("/api/v1/team/pages")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createPageBody())))
                    .andExpect(status().isCreated());
        }

        private Map<String, Object> createPageBody() {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("teamId", teamAId);
            body.put("title", "新規ページ");
            body.put("slug", "mbauthz-new-" + System.nanoTime());
            body.put("pageType", "YEARLY");
            body.put("year", 2099);
            return body;
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 3. GET /api/v1/team/pages/{id}（詳細・bare id: entity由来404存在秘匿）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("3. GET /api/v1/team/pages/{id}（詳細）")
    class GetPage {

        @Test
        @DisplayName("非メンバーは404（存在秘匿）")
        void 非メンバーは404() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get("/api/v1/team/pages/{id}", pageAId))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("別チームADMIN（越境IDでpageAを直指定）は404（BOLA存在秘匿）")
        void 越境IDは404() throws Exception {
            setAuth(adminBId);
            mockMvc.perform(get("/api/v1/team/pages/{id}", pageAId))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("正当メンバーは200")
        void 正当メンバーは200() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(get("/api/v1/team/pages/{id}", pageAId))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 4. PUT/DELETE /api/v1/team/pages/{id}（変更・bare id: entity由来404存在秘匿）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("4. PUT/DELETE /api/v1/team/pages/{id}")
    class UpdateDeletePage {

        @Test
        @DisplayName("別チームADMIN（越境）は更新404")
        void 越境IDは更新404() throws Exception {
            setAuth(adminBId);
            mockMvc.perform(put("/api/v1/team/pages/{id}", pageAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateBody())))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("正当ADMINは更新200")
        void 正当ADMINは更新200() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(put("/api/v1/team/pages/{id}", pageAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateBody())))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("別チームADMIN（越境）は削除404")
        void 越境IDは削除404() throws Exception {
            setAuth(adminBId);
            mockMvc.perform(delete("/api/v1/team/pages/{id}", pageAId))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("正当ADMINは削除204")
        void 正当ADMINは削除204() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(delete("/api/v1/team/pages/{id}", pageA2Id))
                    .andExpect(status().isNoContent());
        }

        private Map<String, Object> updateBody() {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("title", "更新後タイトル");
            body.put("slug", "mbauthz-updated-" + System.nanoTime());
            return body;
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 5. PATCH .../publish・POST/DELETE .../preview-token（bare id: entity由来404）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("5. 公開ステータス変更・プレビュートークン")
    class PublishAndPreviewToken {

        @Test
        @DisplayName("別チームADMIN（越境）は公開ステータス変更404")
        void 越境IDは公開ステータス変更404() throws Exception {
            setAuth(adminBId);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("status", "PUBLISHED");
            mockMvc.perform(patch("/api/v1/team/pages/{id}/publish", pageAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body)))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("正当ADMINは公開ステータス変更200")
        void 正当ADMINは公開ステータス変更200() throws Exception {
            setAuth(adminAId);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("status", "PUBLISHED");
            mockMvc.perform(patch("/api/v1/team/pages/{id}/publish", pageAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body)))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("別チームADMIN（越境）はプレビュートークン発行404")
        void 越境IDはプレビュートークン発行404() throws Exception {
            setAuth(adminBId);
            mockMvc.perform(post("/api/v1/team/pages/{id}/preview-token", pageAId))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("正当ADMINはプレビュートークン発行200")
        void 正当ADMINはプレビュートークン発行200() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(post("/api/v1/team/pages/{id}/preview-token", pageAId))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 6. GET/POST /api/v1/team/pages/{pageId}/sections（一覧・追加: page由来スコープ）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("6. GET/POST /api/v1/team/pages/{pageId}/sections")
    class ListCreateSections {

        @Test
        @DisplayName("別チームADMIN（越境）は一覧404")
        void 越境IDは一覧404() throws Exception {
            setAuth(adminBId);
            mockMvc.perform(get("/api/v1/team/pages/{pageId}/sections", pageAId))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("正当メンバーは一覧200")
        void 正当メンバーは一覧200() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(get("/api/v1/team/pages/{pageId}/sections", pageAId))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("非ADMINメンバーは追加404")
        void 非ADMINメンバーは追加404() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(post("/api/v1/team/pages/{pageId}/sections", pageAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createSectionBody())))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("別チームADMIN（越境）は追加404")
        void 越境IDは追加404() throws Exception {
            setAuth(adminBId);
            mockMvc.perform(post("/api/v1/team/pages/{pageId}/sections", pageAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createSectionBody())))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("正当ADMINは追加201")
        void 正当ADMINは追加201() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(post("/api/v1/team/pages/{pageId}/sections", pageAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createSectionBody())))
                    .andExpect(status().isCreated());
        }

        private Map<String, Object> createSectionBody() {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("sectionType", "TEXT");
            body.put("title", "新規セクション");
            return body;
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 7. PUT/DELETE /api/v1/team/sections/{id}（bare id・pageIdすら無い最重要BOLA箇所）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("7. PUT/DELETE /api/v1/team/sections/{id}（pageId非含有bare id）")
    class UpdateDeleteSection {

        @Test
        @DisplayName("別チームADMIN（越境・URLにpageId無し）は更新404")
        void 越境IDは更新404() throws Exception {
            setAuth(adminBId);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("title", "乗っ取り更新");
            mockMvc.perform(put("/api/v1/team/sections/{id}", sectionAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body)))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("正当ADMINは更新200")
        void 正当ADMINは更新200() throws Exception {
            setAuth(adminAId);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("title", "正規更新");
            mockMvc.perform(put("/api/v1/team/sections/{id}", sectionAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body)))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("別チームADMIN（越境）は削除404")
        void 越境IDは削除404() throws Exception {
            setAuth(adminBId);
            mockMvc.perform(delete("/api/v1/team/sections/{id}", sectionAId))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("正当ADMINは削除204")
        void 正当ADMINは削除204() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(delete("/api/v1/team/sections/{id}", sectionAId))
                    .andExpect(status().isNoContent());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 8. GET /api/v1/team/members（一覧・page由来スコープ）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("8. GET /api/v1/team/members（一覧）")
    class ListMembers {

        @Test
        @DisplayName("別チームADMIN（越境）は404")
        void 越境IDは404() throws Exception {
            setAuth(adminBId);
            mockMvc.perform(get("/api/v1/team/members").param("teamPageId", pageAId.toString()))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("正当メンバーは200")
        void 正当メンバーは200() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(get("/api/v1/team/members").param("teamPageId", pageAId.toString()))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 9. GET/PUT/DELETE /api/v1/team/members/{id}（bare id・最重要BOLA箇所）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("9. GET/PUT/DELETE /api/v1/team/members/{id}（bare id）")
    class GetUpdateDeleteMember {

        @Test
        @DisplayName("別チームADMIN（越境）は詳細404")
        void 越境IDは詳細404() throws Exception {
            setAuth(adminBId);
            mockMvc.perform(get("/api/v1/team/members/{id}", profileAId))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("正当メンバーは詳細200")
        void 正当メンバーは詳細200() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(get("/api/v1/team/members/{id}", profileAId))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("別チームADMIN（越境）は更新404")
        void 越境IDは更新404() throws Exception {
            setAuth(adminBId);
            mockMvc.perform(put("/api/v1/team/members/{id}", profileAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateBody())))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("正当ADMINは更新200")
        void 正当ADMINは更新200() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(put("/api/v1/team/members/{id}", profileAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(updateBody())))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("別チームADMIN（越境）は削除404")
        void 越境IDは削除404() throws Exception {
            setAuth(adminBId);
            mockMvc.perform(delete("/api/v1/team/members/{id}", profileAId))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("正当ADMINは削除204")
        void 正当ADMINは削除204() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(delete("/api/v1/team/members/{id}", profileAId))
                    .andExpect(status().isNoContent());
        }

        private Map<String, Object> updateBody() {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("displayName", "更新後の名前");
            return body;
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 10. POST /api/v1/team/members（作成）・/bulk（一括登録）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("10. POST /api/v1/team/members・/bulk")
    class CreateAndBulkCreateMember {

        @Test
        @DisplayName("非ADMINメンバーは作成404")
        void 非ADMINメンバーは作成404() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(post("/api/v1/team/members")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createMemberBody())))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("別チームADMIN（越境）は作成404")
        void 越境IDは作成404() throws Exception {
            setAuth(adminBId);
            mockMvc.perform(post("/api/v1/team/members")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createMemberBody())))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("正当ADMINは作成201")
        void 正当ADMINは作成201() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(post("/api/v1/team/members")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createMemberBody())))
                    .andExpect(status().isCreated());
        }

        @Test
        @DisplayName("別チームADMIN（越境）は一括登録404")
        void 越境IDは一括登録404() throws Exception {
            setAuth(adminBId);
            mockMvc.perform(post("/api/v1/team/members/bulk")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(bulkBody())))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("正当ADMINは一括登録201")
        void 正当ADMINは一括登録201() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(post("/api/v1/team/members/bulk")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(bulkBody())))
                    .andExpect(status().isCreated());
        }

        private Map<String, Object> createMemberBody() {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("teamPageId", pageAId);
            body.put("displayName", "新規メンバー");
            return body;
        }

        private Map<String, Object> bulkBody() {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("displayName", "一括メンバー");
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("teamPageId", pageAId);
            body.put("members", List.of(item));
            return body;
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 11. POST /api/v1/team/pages/{id}/copy-members（コピー先ADMIN×コピー元メンバー以上の複合認可）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("11. POST /api/v1/team/pages/{id}/copy-members")
    class CopyMembers {

        @Test
        @DisplayName("コピー先ADMINでも、コピー元が無関係チームのページだと404（データ流出防止）")
        void コピー元越境は404() throws Exception {
            setAuth(adminAId); // pageA2（teamA）の ADMIN。だがコピー元 pageB は teamB で無関係
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("sourcePageId", pageBId);
            mockMvc.perform(post("/api/v1/team/pages/{id}/copy-members", pageA2Id)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body)))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("コピー先が無関係チームのページだと404（越境ADMIN拒否）")
        void コピー先越境は404() throws Exception {
            setAuth(adminBId); // teamB の ADMIN。コピー先 pageA2 は teamA で無関係
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("sourcePageId", pageAId);
            mockMvc.perform(post("/api/v1/team/pages/{id}/copy-members", pageA2Id)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body)))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("同一チーム内（コピー先ADMIN・コピー元メンバー以上）は200")
        void 同一チーム内は200() throws Exception {
            setAuth(adminAId); // teamA の ADMIN。コピー先 pageA2 もコピー元 pageA も teamA
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("sourcePageId", pageAId);
            mockMvc.perform(post("/api/v1/team/pages/{id}/copy-members", pageA2Id)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body)))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 12. PATCH /api/v1/team/members/reorder（越境ID混入書き込みBOLA是正）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("12. PATCH /api/v1/team/members/reorder")
    class ReorderMembers {

        @Test
        @DisplayName("別チームADMIN（越境・teamPageId=pageAを指定）は404")
        void 越境IDは404() throws Exception {
            setAuth(adminBId);
            mockMvc.perform(patch("/api/v1/team/members/reorder")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(reorderBody(profileAId))))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("正当ADMINは200・他ページのidを紛れ込ませても他ページのsortOrderは不変（BOLA是正）")
        void 正当ADMINは200_越境id混入は無視される() throws Exception {
            setAuth(adminAId);
            MemberProfileEntity beforeB = profileRepository.findById(profileBId).orElseThrow();
            Integer beforeSortOrder = beforeB.getSortOrder();

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("teamPageId", pageAId);
            Map<String, Object> ownItem = new LinkedHashMap<>();
            ownItem.put("id", profileAId);
            ownItem.put("sortOrder", 9);
            Map<String, Object> foreignItem = new LinkedHashMap<>();
            foreignItem.put("id", profileBId); // teamB 配下の他ページ id を紛れ込ませる
            foreignItem.put("sortOrder", 9);
            body.put("orders", List.of(ownItem, foreignItem));

            mockMvc.perform(patch("/api/v1/team/members/reorder")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body)))
                    .andExpect(status().isOk());

            MemberProfileEntity afterA = profileRepository.findById(profileAId).orElseThrow();
            MemberProfileEntity afterB = profileRepository.findById(profileBId).orElseThrow();
            assertThat(afterA.getSortOrder()).isEqualTo(9);
            assertThat(afterB.getSortOrder()).isEqualTo(beforeSortOrder); // 越境書き込みされていないこと
        }

        private Map<String, Object> reorderBody(Long profileId) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", profileId);
            item.put("sortOrder", 1);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("teamPageId", pageAId);
            body.put("orders", List.of(item));
            return body;
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 13. GET /api/v1/team/members/lookup（検索・page由来スコープ）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("13. GET /api/v1/team/members/lookup")
    class LookupMembers {

        @Test
        @DisplayName("別チームADMIN（越境）は404")
        void 越境IDは404() throws Exception {
            setAuth(adminBId);
            mockMvc.perform(get("/api/v1/team/members/lookup")
                            .param("q", "選手")
                            .param("teamPageId", pageAId.toString()))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("正当メンバーは200")
        void 正当メンバーは200() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(get("/api/v1/team/members/lookup")
                            .param("q", "選手")
                            .param("teamPageId", pageAId.toString()))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 14. GET /api/v1/team/member-fields（一覧・スコープ宣言型: checkMembership）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("14. GET /api/v1/team/member-fields（一覧）")
    class ListFields {

        @Test
        @DisplayName("非メンバーは403")
        void 非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get("/api/v1/team/member-fields").param("teamId", teamAId.toString()))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別チームADMIN（越境）は403")
        void 越境は403() throws Exception {
            setAuth(adminBId);
            mockMvc.perform(get("/api/v1/team/member-fields").param("teamId", teamAId.toString()))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当メンバーは200")
        void 正当メンバーは200() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(get("/api/v1/team/member-fields").param("teamId", teamAId.toString()))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 15. POST /api/v1/team/member-fields（作成・スコープ宣言型: checkAdminOrAbove）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("15. POST /api/v1/team/member-fields（作成）")
    class CreateField {

        @Test
        @DisplayName("非ADMINメンバーは403")
        void 非ADMINメンバーは403() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(post("/api/v1/team/member-fields")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createFieldBody())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別チームADMIN（越境）は403")
        void 越境は403() throws Exception {
            setAuth(adminBId);
            mockMvc.perform(post("/api/v1/team/member-fields")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createFieldBody())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINは201")
        void 正当ADMINは201() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(post("/api/v1/team/member-fields")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(createFieldBody())))
                    .andExpect(status().isCreated());
        }

        private Map<String, Object> createFieldBody() {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("teamId", teamAId);
            body.put("fieldName", "新規フィールド");
            body.put("fieldType", "TEXT");
            return body;
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 16. PUT/DELETE /api/v1/team/member-fields/{id}（bare id: entity由来404）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("16. PUT/DELETE /api/v1/team/member-fields/{id}")
    class UpdateDeleteField {

        @Test
        @DisplayName("別チームADMIN（越境）は更新404")
        void 越境IDは更新404() throws Exception {
            setAuth(adminBId);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("fieldName", "乗っ取り更新");
            mockMvc.perform(put("/api/v1/team/member-fields/{id}", fieldAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body)))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("正当ADMINは更新200")
        void 正当ADMINは更新200() throws Exception {
            setAuth(adminAId);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("fieldName", "正規更新");
            mockMvc.perform(put("/api/v1/team/member-fields/{id}", fieldAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body)))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("別チームADMIN（越境）は無効化404")
        void 越境IDは無効化404() throws Exception {
            setAuth(adminBId);
            mockMvc.perform(delete("/api/v1/team/member-fields/{id}", fieldAId))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("正当ADMINは無効化204")
        void 正当ADMINは無効化204() throws Exception {
            setAuth(adminAId);
            mockMvc.perform(delete("/api/v1/team/member-fields/{id}", fieldAId))
                    .andExpect(status().isNoContent());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // ヘルパー
    // ═════════════════════════════════════════════════════════════════════

    private void setAuth(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
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
                                + "VALUES (:email, 'MBAUTHZ', 'テスト', 'MBAUTHZ テスト', 'ACTIVE', "
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
