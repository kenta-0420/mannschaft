package com.mannschaft.app.tournament.entry;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 認可根治戦役 Wave7 — tournament エントリー表／エントリーテンプレート API 契約テスト。
 *
 * <p><b>認可 scope の判定方針</b>: {@code TournamentEntryMemberService} と
 * {@code TournamentEntryTemplateService} の各操作は、Controller から渡される
 * {@code currentUserId} を参加チーム（<b>エンティティ由来</b> {@code participant.teamId}）
 * または主催組織（<b>エンティティ由来</b> {@code tournament.organizationId}）の scope に対して
 * {@code AccessControlService} で判定する。既存の {@code resolveParticipant} /
 * {@code validateTeamBelongsToOrg} は orgId↔tId↔divId↔pId の階層整合性検査であり、
 * この scope 認可とは独立した別の検証として両方を敷く。</p>
 *
 * <p><b>本テストの主眼</b>: 引数を足しただけで終わっていないこと ——
 * {@code currentUserId} が実際に認可判定に使われていることを、
 * 「非メンバーが 403 になる」「非 ADMIN メンバーが管理操作で 403 になる」ことによって
 * 機械的に証明する。同一 URL・同一 body で<b>認証主体だけを差し替えて</b>結果が変わることが、
 * 引数が生きている証拠になる。</p>
 *
 * <p>金型: {@code EquipmentScopeContractIT} / {@code TournamentScopeContractIT}
 * （{@code @AutoConfigureMockMvc(addFilters=false)} + 実 MySQL + 手動 SecurityContext +
 * {@code MembershipTestHelper}）。ADMIN 役は {@code checkAdminOrAbove}（user_roles）と
 * {@code isMember}（memberships）の別系統を両方満たすよう二重に seed する。</p>
 *
 * <p><b>象限</b>: 非メンバー（outsider）/ 別 scope ADMIN（BOLA: orgB の ADMIN が orgA の
 * エントリーへ）/ 非 ADMIN メンバー / 正当な権限。加えて他組織の {@code participantId} /
 * {@code templateId} を直接指定する越境（404 存在秘匿）を検証する。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("tournament エントリー表・テンプレート 認可契約テスト（Wave7）")
class TournamentEntryScopeContractIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private TournamentEntryTemplateRepository templateRepository;

    @Autowired
    private TournamentEntryMemberRepository entryMemberRepository;

    @PersistenceContext
    private EntityManager em;

    private Long orgAId;
    private Long orgBId;
    private Long teamAId;
    private Long teamBId;

    /** ORG A の ADMIN（主催者・正当） */
    private Long orgAdminAId;
    /** ORG B の ADMIN（別 scope からの越境検証用） */
    private Long orgAdminBId;
    /** TEAM A の ADMIN（参加チーム管理者・正当） */
    private Long teamAdminAId;
    /** TEAM A の非 ADMIN メンバー（閲覧可・管理不可） */
    private Long teamMemberAId;
    /** どこにも所属しない非メンバー */
    private Long outsiderId;

    private Long tAId;
    private Long tBId;
    private Long divAId;
    private Long divBId;
    /** orgA / teamA の参加チーム（被害者リソース） */
    private Long pAId;
    /** orgB / teamB の参加チーム（越境指定用） */
    private Long pBId;

    private UUID templateAId;
    private UUID templateBId;
    private UUID entryMemberAId;

    @BeforeEach
    void setUp() {
        insertRoleIfAbsent("ADMIN", "管理者", 2);
        insertRoleIfAbsent("MEMBER", "メンバー", 4);

        orgAId = insertOrganization("W7ENTRY組織A");
        orgBId = insertOrganization("W7ENTRY組織B");
        teamAId = insertTeam("W7ENTRYチームA");
        teamBId = insertTeam("W7ENTRYチームB");

        // validateTeamBelongsToOrg（テンプレート系の org 束縛）の前提
        insertTeamOrgMembership(teamAId, orgAId);
        insertTeamOrgMembership(teamBId, orgBId);

        orgAdminAId = insertUser("w7entry-orgadmin-a@example.com");
        orgAdminBId = insertUser("w7entry-orgadmin-b@example.com");
        teamAdminAId = insertUser("w7entry-teamadmin-a@example.com");
        teamMemberAId = insertUser("w7entry-teammember-a@example.com");
        outsiderId = insertUser("w7entry-outsider@example.com");

        // ADMIN 役は user_roles（checkAdminOrAbove）と memberships（isMember）の両系統に seed
        MembershipTestHelper.insertUserRole(em, orgAdminAId, "ADMIN", null, orgAId);
        MembershipTestHelper.insertMembership(em, orgAdminAId, ScopeType.ORGANIZATION, orgAId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, orgAdminBId, "ADMIN", null, orgBId);
        MembershipTestHelper.insertMembership(em, orgAdminBId, ScopeType.ORGANIZATION, orgBId, RoleKind.MEMBER);

        MembershipTestHelper.insertUserRole(em, teamAdminAId, "ADMIN", teamAId, null);
        MembershipTestHelper.insertMembership(em, teamAdminAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);

        MembershipTestHelper.insertMembership(em, teamMemberAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);

        // outsiderId はどこにも所属させない

        tAId = insertTournament(orgAId, "W7ENTRY大会A", "PUBLIC", "OPEN", orgAdminAId);
        tBId = insertTournament(orgBId, "W7ENTRY大会B", "MEMBERS_AND_ABOVE", "OPEN", orgAdminBId);
        divAId = insertDivision(tAId, "W7ENTRY A1部");
        divBId = insertDivision(tBId, "W7ENTRY B1部");
        pAId = insertParticipant(divAId, teamAId);
        pBId = insertParticipant(divBId, teamBId);

        templateAId = templateRepository.save(TournamentEntryTemplateEntity.builder()
                .teamId(teamAId).name("W7ENTRYテンプレA").sortOrder((short) 0)
                .build()).getId();
        templateBId = templateRepository.save(TournamentEntryTemplateEntity.builder()
                .teamId(teamBId).name("W7ENTRYテンプレB").sortOrder((short) 0)
                .build()).getId();

        entryMemberAId = entryMemberRepository.save(TournamentEntryMemberEntity.builder()
                .participantId(pAId).userId(teamMemberAId).sortOrder((short) 0)
                .build()).getId();

        em.flush();
        em.clear();
    }

    // ═════════════════════════════════════════════════════════════════════
    // 1. GET entry-members（閲覧: 参加チーム MEMBER+ or 主催組織 ADMIN）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("1. GET entry-members（エントリー表一覧）")
    class GetEntryMembers {

        @Test
        @DisplayName("非メンバーは403（currentUserId が認可に使われている証拠）")
        void 非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get(ENTRY_MEMBERS, orgAId, tAId, divAId, pAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMIN（orgBのADMIN）は403（BOLA）")
        void 別scopeADMINは403() throws Exception {
            setAuth(orgAdminBId);
            mockMvc.perform(get(ENTRY_MEMBERS, orgAId, tAId, divAId, pAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("参加チームの非ADMINメンバーは200（閲覧はMEMBER+）")
        void 非ADMINメンバーは200() throws Exception {
            setAuth(teamMemberAId);
            mockMvc.perform(get(ENTRY_MEMBERS, orgAId, tAId, divAId, pAId))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("主催組織ADMINは200")
        void 主催組織ADMINは200() throws Exception {
            setAuth(orgAdminAId);
            mockMvc.perform(get(ENTRY_MEMBERS, orgAId, tAId, divAId, pAId))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("他組織の大会IDを自組織パスに混ぜると404（org束縛・存在秘匿）")
        void 他組織大会は404() throws Exception {
            setAuth(orgAdminAId);
            mockMvc.perform(get(ENTRY_MEMBERS, orgAId, tBId, divBId, pBId))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("他組織のparticipantIdを直接指定しても404（div束縛・存在秘匿）")
        void 他組織participantは404() throws Exception {
            setAuth(teamAdminAId);
            mockMvc.perform(get(ENTRY_MEMBERS, orgAId, tAId, divAId, pBId))
                    .andExpect(status().isNotFound());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 2. POST load-from-team（編集: 参加チーム ADMIN or 主催組織 ADMIN）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("2. POST entry-members/load-from-team（一括ロード）")
    class LoadFromTeam {

        @Test
        @DisplayName("非メンバーは403")
        void 非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(post(ENTRY_MEMBERS + "/load-from-team", orgAId, tAId, divAId, pAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("overwriteExisting", false))))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("参加チームの非ADMINメンバーは403（閲覧はできるが編集は不可）")
        void 非ADMINメンバーは403() throws Exception {
            setAuth(teamMemberAId);
            mockMvc.perform(post(ENTRY_MEMBERS + "/load-from-team", orgAId, tAId, divAId, pAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("overwriteExisting", false))))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMINは403（BOLA）")
        void 別scopeADMINは403() throws Exception {
            setAuth(orgAdminBId);
            mockMvc.perform(post(ENTRY_MEMBERS + "/load-from-team", orgAId, tAId, divAId, pAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("overwriteExisting", false))))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("参加チームADMINは200")
        void 参加チームADMINは200() throws Exception {
            setAuth(teamAdminAId);
            mockMvc.perform(post(ENTRY_MEMBERS + "/load-from-team", orgAId, tAId, divAId, pAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("overwriteExisting", false))))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 3. PUT entry-members（全置換）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("3. PUT entry-members（全置換）")
    class UpsertEntryMembers {

        @Test
        @DisplayName("参加チームの非ADMINメンバーは403")
        void 非ADMINメンバーは403() throws Exception {
            setAuth(teamMemberAId);
            mockMvc.perform(put(ENTRY_MEMBERS, orgAId, tAId, divAId, pAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(upsertBody())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMINは403（BOLA）")
        void 別scopeADMINは403() throws Exception {
            setAuth(orgAdminBId);
            mockMvc.perform(put(ENTRY_MEMBERS, orgAId, tAId, divAId, pAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(upsertBody())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("参加チームADMINは200")
        void 参加チームADMINは200() throws Exception {
            setAuth(teamAdminAId);
            mockMvc.perform(put(ENTRY_MEMBERS, orgAId, tAId, divAId, pAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(upsertBody())))
                    .andExpect(status().isOk());
        }

        private Map<String, Object> upsertBody() {
            Map<String, Object> member = new LinkedHashMap<>();
            member.put("userId", teamMemberAId);
            member.put("sortOrder", 0);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("members", List.of(member));
            return body;
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 4. DELETE entry-members/{entryMemberId}（個別削除）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("4. DELETE entry-members/{entryMemberId}（個別削除）")
    class DeleteEntryMember {

        @Test
        @DisplayName("非メンバーは403")
        void 非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(delete(ENTRY_MEMBERS + "/{emId}", orgAId, tAId, divAId, pAId, entryMemberAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("参加チームの非ADMINメンバーは403")
        void 非ADMINメンバーは403() throws Exception {
            setAuth(teamMemberAId);
            mockMvc.perform(delete(ENTRY_MEMBERS + "/{emId}", orgAId, tAId, divAId, pAId, entryMemberAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("参加チームADMINは204")
        void 参加チームADMINは204() throws Exception {
            setAuth(teamAdminAId);
            mockMvc.perform(delete(ENTRY_MEMBERS + "/{emId}", orgAId, tAId, divAId, pAId, entryMemberAId))
                    .andExpect(status().isNoContent());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 5. GET entry-members/pdf（実名一覧の PDF 出力・閲覧系）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("5. GET entry-members/pdf（エントリー表PDF）")
    class GenerateEntryPdf {

        @Test
        @DisplayName("非メンバーは403（実名一覧のPDF吸い出しを遮断）")
        void 非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get(ENTRY_MEMBERS + "/pdf", orgAId, tAId, divAId, pAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMINは403（BOLA）")
        void 別scopeADMINは403() throws Exception {
            setAuth(orgAdminBId);
            mockMvc.perform(get(ENTRY_MEMBERS + "/pdf", orgAId, tAId, divAId, pAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("参加チームの非ADMINメンバーは200")
        void 非ADMINメンバーは200() throws Exception {
            setAuth(teamMemberAId);
            mockMvc.perform(get(ENTRY_MEMBERS + "/pdf", orgAId, tAId, divAId, pAId))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 6. GET entry-summary（主催者向け横断集計: 主催組織 ADMIN 限定）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("6. GET entry-summary（全チームサマリー・主催者向け）")
    class GetEntrySummary {

        @Test
        @DisplayName("非メンバーは403")
        void 非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get(ENTRY_SUMMARY, orgAId, tAId, divAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("参加チームADMINでも403（主催者向けのため主催組織ADMIN限定）")
        void 参加チームADMINは403() throws Exception {
            setAuth(teamAdminAId);
            mockMvc.perform(get(ENTRY_SUMMARY, orgAId, tAId, divAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMINは403（BOLA）")
        void 別scopeADMINは403() throws Exception {
            setAuth(orgAdminBId);
            mockMvc.perform(get(ENTRY_SUMMARY, orgAId, tAId, divAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("主催組織ADMINは200")
        void 主催組織ADMINは200() throws Exception {
            setAuth(orgAdminAId);
            mockMvc.perform(get(ENTRY_SUMMARY, orgAId, tAId, divAId))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 7. GET/POST entry-templates（一覧・作成）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("7. GET/POST entry-templates（一覧・作成）")
    class TemplateListAndCreate {

        @Test
        @DisplayName("非メンバーは一覧403")
        void 非メンバーは一覧403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get(TEMPLATES, orgAId, teamAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMINは一覧403（BOLA）")
        void 別scopeADMINは一覧403() throws Exception {
            setAuth(orgAdminBId);
            mockMvc.perform(get(TEMPLATES, orgAId, teamAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("チームの非ADMINメンバーは一覧200")
        void 非ADMINメンバーは一覧200() throws Exception {
            setAuth(teamMemberAId);
            mockMvc.perform(get(TEMPLATES, orgAId, teamAId))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("他組織パスに他組織チームを渡すと404（org束縛・存在秘匿）")
        void 他組織チームは404() throws Exception {
            setAuth(orgAdminAId);
            mockMvc.perform(get(TEMPLATES, orgAId, teamBId))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("チームの非ADMINメンバーは作成403")
        void 非ADMINメンバーは作成403() throws Exception {
            setAuth(teamMemberAId);
            mockMvc.perform(post(TEMPLATES, orgAId, teamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(templateBody("新規テンプレ"))))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("チームADMINは作成201")
        void チームADMINは作成201() throws Exception {
            setAuth(teamAdminAId);
            mockMvc.perform(post(TEMPLATES, orgAId, teamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(templateBody("新規テンプレ"))))
                    .andExpect(status().isCreated());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 8. GET/PUT/DELETE entry-templates/{templateId}（詳細・更新・削除）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("8. GET/PUT/DELETE entry-templates/{templateId}")
    class TemplateDetailUpdateDelete {

        @Test
        @DisplayName("非メンバーは詳細403（登録選手のuserId＋実名を遮断）")
        void 非メンバーは詳細403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get(TEMPLATES + "/{tplId}", orgAId, teamAId, templateAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("チームの非ADMINメンバーは詳細200")
        void 非ADMINメンバーは詳細200() throws Exception {
            setAuth(teamMemberAId);
            mockMvc.perform(get(TEMPLATES + "/{tplId}", orgAId, teamAId, templateAId))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("他組織のtemplateIdを直接指定しても404（team束縛・存在秘匿）")
        void 他組織テンプレは404() throws Exception {
            setAuth(teamAdminAId);
            mockMvc.perform(get(TEMPLATES + "/{tplId}", orgAId, teamAId, templateBId))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("チームの非ADMINメンバーは更新403（bodyは充足させ bind 400 を避ける）")
        void 非ADMINメンバーは更新403() throws Exception {
            setAuth(teamMemberAId);
            mockMvc.perform(put(TEMPLATES + "/{tplId}", orgAId, teamAId, templateAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(templateBody("更新後テンプレ"))))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMINは更新403（BOLA）")
        void 別scopeADMINは更新403() throws Exception {
            setAuth(orgAdminBId);
            mockMvc.perform(put(TEMPLATES + "/{tplId}", orgAId, teamAId, templateAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(templateBody("乗っ取り更新"))))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("チームADMINは更新200")
        void チームADMINは更新200() throws Exception {
            setAuth(teamAdminAId);
            mockMvc.perform(put(TEMPLATES + "/{tplId}", orgAId, teamAId, templateAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(templateBody("更新後テンプレ"))))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("チームの非ADMINメンバーは削除403")
        void 非ADMINメンバーは削除403() throws Exception {
            setAuth(teamMemberAId);
            mockMvc.perform(delete(TEMPLATES + "/{tplId}", orgAId, teamAId, templateAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("チームADMINは削除204")
        void チームADMINは削除204() throws Exception {
            setAuth(teamAdminAId);
            mockMvc.perform(delete(TEMPLATES + "/{tplId}", orgAId, teamAId, templateAId))
                    .andExpect(status().isNoContent());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 9. POST entry-members/apply-template（テンプレート適用・編集系）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("9. POST entry-members/apply-template（テンプレート適用）")
    class ApplyTemplate {

        @Test
        @DisplayName("非メンバーは403")
        void 非メンバーは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(post(ENTRY_MEMBERS + "/apply-template", orgAId, tAId, divAId, pAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(applyBody())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("参加チームの非ADMINメンバーは403")
        void 非ADMINメンバーは403() throws Exception {
            setAuth(teamMemberAId);
            mockMvc.perform(post(ENTRY_MEMBERS + "/apply-template", orgAId, tAId, divAId, pAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(applyBody())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("別scope ADMINは403（BOLA）")
        void 別scopeADMINは403() throws Exception {
            setAuth(orgAdminBId);
            mockMvc.perform(post(ENTRY_MEMBERS + "/apply-template", orgAId, tAId, divAId, pAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(applyBody())))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("参加チームADMINは200")
        void 参加チームADMINは200() throws Exception {
            setAuth(teamAdminAId);
            mockMvc.perform(post(ENTRY_MEMBERS + "/apply-template", orgAId, tAId, divAId, pAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(applyBody())))
                    .andExpect(status().isOk());
        }

        private Map<String, Object> applyBody() {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("templateId", templateAId.toString());
            body.put("overwriteExisting", false);
            return body;
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // ヘルパー
    // ═════════════════════════════════════════════════════════════════════

    private static final String ENTRY_MEMBERS =
            "/api/v1/organizations/{orgId}/tournaments/{tId}/divisions/{divId}/participants/{pId}/entry-members";
    private static final String ENTRY_SUMMARY =
            "/api/v1/organizations/{orgId}/tournaments/{tId}/divisions/{divId}/entry-summary";
    private static final String TEMPLATES =
            "/api/v1/organizations/{orgId}/teams/{teamId}/entry-templates";

    private Map<String, Object> templateBody(String name) {
        Map<String, Object> member = new LinkedHashMap<>();
        member.put("userId", teamMemberAId);
        member.put("sortOrder", 0);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("sortOrder", 0);
        body.put("members", List.of(member));
        return body;
    }

    private void setAuth(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
    }

    /** roles はグローバル参照テーブルのため deleteAll せず name で引く idempotent seed。 */
    private void insertRoleIfAbsent(String name, String displayName, int priority) {
        Number count = (Number) em.createNativeQuery("SELECT COUNT(*) FROM roles WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult();
        if (count.longValue() > 0) {
            return;
        }
        em.createNativeQuery(
                        "INSERT INTO roles (name, display_name, priority, is_system, created_at, updated_at) "
                                + "VALUES (:name, :dn, :priority, 0, NOW(), NOW())")
                .setParameter("name", name)
                .setParameter("dn", displayName)
                .setParameter("priority", priority)
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
                                + "VALUES (:email, 'W7Entry', 'テスト', 'W7ENTRY契約テスト', 'ACTIVE', "
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

    private Long insertOrganization(String name) {
        em.createNativeQuery(
                        "INSERT INTO organizations (name, org_type, visibility, hierarchy_visibility, "
                                + "supporter_enabled, version, slug, created_at, updated_at) "
                                + "VALUES (:name, 'OTHER', 'PUBLIC', 'NONE', 1, 0, "
                                + "CONCAT('w7e-', LEFT(REPLACE(UUID(),'-',''),8)), NOW(), NOW())")
                .setParameter("name", name)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM organizations WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult()).longValue();
    }

    private Long insertTeam(String name) {
        em.createNativeQuery(
                        "INSERT INTO teams (name, visibility, supporter_enabled, version, member_count, slug, "
                                + "created_at, updated_at) "
                                + "VALUES (:name, 'PUBLIC', 1, 0, 0, "
                                + "CONCAT('w7e-', LEFT(REPLACE(UUID(),'-',''),8)), NOW(), NOW())")
                .setParameter("name", name)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM teams WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult()).longValue();
    }

    /** テンプレート系の {@code validateTeamBelongsToOrg}（org 束縛）が ACTIVE 行を要求する。 */
    private void insertTeamOrgMembership(Long teamId, Long organizationId) {
        em.createNativeQuery(
                        "INSERT INTO team_org_memberships (team_id, organization_id, status, "
                                + "invited_at, created_at) "
                                + "VALUES (:teamId, :orgId, 'ACTIVE', NOW(), NOW())")
                .setParameter("teamId", teamId)
                .setParameter("orgId", organizationId)
                .executeUpdate();
    }

    /**
     * 大会を seed する。entity の NOT NULL 列（@Builder.Default は DDL デフォルトを生成しない）を
     * すべて明示指定する（TournamentScopeContractIT 踏襲）。
     */
    private Long insertTournament(Long orgId, String name, String visibility, String status, Long createdBy) {
        em.createNativeQuery(
                        "INSERT INTO tournaments (organization_id, name, format, sport, "
                                + "win_points, draw_points, loss_points, has_draw, has_sets, "
                                + "has_extra_time, has_penalties, score_unit_label, league_round_type, "
                                + "knockout_legs, visibility, status, version, created_by, created_at, updated_at) "
                                + "VALUES (:orgId, :name, 'LEAGUE', 'SOCCER', "
                                + "3, 1, 0, 1, 0, "
                                + "0, 0, '点', 'SINGLE', "
                                + "1, :vis, :status, 0, :createdBy, NOW(), NOW())")
                .setParameter("orgId", orgId)
                .setParameter("name", name)
                .setParameter("vis", visibility)
                .setParameter("status", status)
                .setParameter("createdBy", createdBy)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM tournaments WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult()).longValue();
    }

    private Long insertDivision(Long tournamentId, String name) {
        em.createNativeQuery(
                        "INSERT INTO tournament_divisions (tournament_id, name, level, promotion_slots, "
                                + "relegation_slots, playoff_promotion_slots, sort_order, created_at, updated_at) "
                                + "VALUES (:tid, :name, 1, 0, 0, 0, 0, NOW(), NOW())")
                .setParameter("tid", tournamentId)
                .setParameter("name", name)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM tournament_divisions WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult()).longValue();
    }

    private Long insertParticipant(Long divisionId, Long teamId) {
        em.createNativeQuery(
                        "INSERT INTO tournament_participants (division_id, team_id, status, joined_at) "
                                + "VALUES (:divId, :teamId, 'REGISTERED', NOW())")
                .setParameter("divId", divisionId)
                .setParameter("teamId", teamId)
                .executeUpdate();
        return ((Number) em.createNativeQuery(
                        "SELECT MAX(id) FROM tournament_participants WHERE division_id = :divId")
                .setParameter("divId", divisionId)
                .getSingleResult()).longValue();
    }
}
