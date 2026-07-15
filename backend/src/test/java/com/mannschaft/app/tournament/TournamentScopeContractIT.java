package com.mannschaft.app.tournament;

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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 認可根治戦役 Wave2 トランシェ2C: tournament ドメイン API 契約テスト（試練）。
 *
 * <p>正本: {@code .claude/campaigns/2026-07-10-authz-idor-audit.md}（tournament 節）・
 * {@code AccessControlService}（{@code checkAdminOrAbove}/{@code checkMembership}）・
 * F00 共通可視性 Resolver（{@code TournamentVisibilityResolver}）。
 * 金型: {@code ParkingScopeContractIT}
 * （{@code @AutoConfigureMockMvc(addFilters=false)} + 実 MySQL・
 * 越境 403/404 はアプリ層例外として認可フィルタ無効でも検証できる）。</p>
 *
 * <p>担当スコープ（他は対象外）:</p>
 * <ul>
 *   <li>DivisionController/DivisionService: 認可完全欠落の是正
 *       （閲覧=大会可視性 404 秘匿、変更=主催組織 ADMIN。divId/pId の親大会束縛）</li>
 *   <li>FixtureController/FixtureService: path orgId 信用の BOLA 是正
 *       （tId↔orgId・divId↔tId・matchId↔tId のエンティティ束縛。batch の他大会 matchId 混入遮断）</li>
 *   <li>PromotionController/PromotionService: 認可完全欠落の是正
 *       （実行/プレビュー=主催組織 ADMIN、履歴=大会可視性。entries の division 束縛）</li>
 *   <li>TournamentTemplateController/TournamentTemplateService: 認可完全欠落＋
 *       findById 全テナント串刺しの是正（閲覧=組織メンバー、変更=組織 ADMIN、org 束縛 404 秘匿）</li>
 *   <li>PublicTournamentController/EmbedController: 公開大会の tId を踏み台に
 *       非公開大会の divId を閲覧できる穴（台帳指摘）の閉塞。正当な匿名閲覧（PUBLIC 大会）は非破壊</li>
 * </ul>
 *
 * <p>ADMIN 役の被験者は {@code checkMembership}（memberships 表）と
 * {@code checkAdminOrAbove}（user_roles 表）の両方を満たすよう二重に seed する
 * （認可根治戦役 Wave0+1 で確立した既知の地雷）。roles はグローバル参照テーブルのため
 * deleteAll せず name で引く idempotent seed とする。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("tournament ドメイン API 契約テスト（認可根治 Wave2 トランシェ2C）")
class TournamentScopeContractIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @PersistenceContext
    private EntityManager em;

    private Long orgAId;
    private Long orgBId;
    private Long teamAId;
    private Long adminAId;
    private Long adminBId;
    private Long memberAId;
    private Long outsiderId;

    /** 公開大会（orgA・PUBLIC×OPEN・匿名閲覧可） */
    private Long tPubA;
    /** 非公開大会（orgA・MEMBERS_AND_ABOVE×OPEN） */
    private Long tPrivA;
    /** 非公開大会（orgB・MEMBERS_AND_ABOVE×OPEN・被害者スコープ） */
    private Long tPrivB;

    private Long divPubA;
    private Long divPrivB;
    private Long matchdayPubA;
    private Long matchPrivB;
    private Long templateAId;

    @BeforeEach
    void setUp() {
        insertRoleIfAbsent("ADMIN", "管理者", 2);
        insertRoleIfAbsent("MEMBER", "メンバー", 4);

        orgAId = insertOrganization("TOUR92C契約テスト組織A");
        orgBId = insertOrganization("TOUR92C契約テスト組織B");
        teamAId = insertTeam("TOUR92C契約テストチームA");

        adminAId = insertUser("tour92c-admin-a@example.com");
        adminBId = insertUser("tour92c-admin-b@example.com");
        memberAId = insertUser("tour92c-member-a@example.com");
        outsiderId = insertUser("tour92c-outsider@example.com");

        // ADMIN 役は checkMembership(memberships) と checkAdminOrAbove(user_roles) の両方を満たす必要がある
        MembershipTestHelper.insertUserRole(em, adminAId, "ADMIN", null, orgAId);
        MembershipTestHelper.insertMembership(em, adminAId, ScopeType.ORGANIZATION, orgAId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminBId, "ADMIN", null, orgBId);
        MembershipTestHelper.insertMembership(em, adminBId, ScopeType.ORGANIZATION, orgBId, RoleKind.MEMBER);

        // memberA は orgA の一般メンバー（ADMIN 権限なし）
        MembershipTestHelper.insertMembership(em, memberAId, ScopeType.ORGANIZATION, orgAId, RoleKind.MEMBER);

        // outsiderId はどの組織にも一切所属しない

        tPubA = insertTournament(orgAId, "TOUR92C公開大会A", "PUBLIC", "OPEN", adminAId);
        tPrivA = insertTournament(orgAId, "TOUR92C非公開大会A", "MEMBERS_AND_ABOVE", "OPEN", adminAId);
        tPrivB = insertTournament(orgBId, "TOUR92C非公開大会B", "MEMBERS_AND_ABOVE", "OPEN", adminBId);

        divPubA = insertDivision(tPubA, "TOUR92C公開A1部");
        divPrivB = insertDivision(tPrivB, "TOUR92C非公開B1部");
        matchdayPubA = insertMatchday(divPubA, "第1節");
        Long matchdayPrivB = insertMatchday(divPrivB, "第1節");
        matchPrivB = insertFixture(matchdayPrivB);

        templateAId = insertTemplate(orgAId, "TOUR92CテンプレA", adminAId);

        em.flush();
        em.clear();
    }

    // ═════════════════════════════════════════════════════════════════════
    // Division（ディビジョン・参加チーム）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("ディビジョン(division)・参加チーム(participant)")
    class Division {

        @Test
        @DisplayName("非メンバーの非公開大会ディビジョン一覧は404（可視性 fail-closed）")
        void 非メンバーの非公開大会ディビジョン一覧は404() throws Exception {
            setAuthentication(outsiderId);

            mockMvc.perform(get("/api/v1/organizations/{orgId}/tournaments/{tId}/divisions", orgAId, tPrivA))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("公開大会のディビジョン一覧は匿名でも200（正当な公開経路は非破壊）")
        void 公開大会のディビジョン一覧は匿名でも200() throws Exception {
            clearAuthentication();

            mockMvc.perform(get("/api/v1/organizations/{orgId}/tournaments/{tId}/divisions", orgAId, tPubA))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("非ADMINメンバーのディビジョン作成は403")
        void 非ADMINメンバーのディビジョン作成は403() throws Exception {
            setAuthentication(memberAId);

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("name", "TOUR92C新設1部");

            mockMvc.perform(post("/api/v1/organizations/{orgId}/tournaments/{tId}/divisions", orgAId, tPubA)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body)))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("他組織ADMINが自組織URLで他組織大会にディビジョンを作成しようとすると404（BOLA是正）")
        void 他組織ADMINによる越境ディビジョン作成は404() throws Exception {
            // orgB の ADMIN が、自分の orgB の URL パスに「orgA の大会 ID」を指定する
            setAuthentication(adminBId);

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("name", "TOUR92C乗っ取り1部");

            mockMvc.perform(post("/api/v1/organizations/{orgId}/tournaments/{tId}/divisions", orgBId, tPubA)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("TOUR_001"));
        }

        @Test
        @DisplayName("正当ADMINのディビジョン更新は200")
        void 正当ADMINのディビジョン更新は200() throws Exception {
            setAuthentication(adminAId);

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("name", "TOUR92C改名1部");

            mockMvc.perform(patch("/api/v1/organizations/{orgId}/tournaments/{tId}/divisions/{divId}",
                            orgAId, tPubA, divPubA)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body)))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("公開大会のtIdを踏み台に非公開大会divIdの参加チーム一覧を見ると404（divId束縛）")
        void 公開大会踏み台の非公開div参加チーム一覧は404() throws Exception {
            clearAuthentication();

            mockMvc.perform(get(
                            "/api/v1/organizations/{orgId}/tournaments/{tId}/divisions/{divId}/participants",
                            orgAId, tPubA, divPrivB))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("TOUR_002"));
        }

        @Test
        @DisplayName("正当ADMINの参加チーム追加は201")
        void 正当ADMINの参加チーム追加は201() throws Exception {
            setAuthentication(adminAId);

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("teamId", teamAId);

            mockMvc.perform(post(
                            "/api/v1/organizations/{orgId}/tournaments/{tId}/divisions/{divId}/participants",
                            orgAId, tPubA, divPubA)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body)))
                    .andExpect(status().isCreated());
        }

        @Test
        @DisplayName("非ADMINメンバーの参加チーム追加は403")
        void 非ADMINメンバーの参加チーム追加は403() throws Exception {
            setAuthentication(memberAId);

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("teamId", teamAId);

            mockMvc.perform(post(
                            "/api/v1/organizations/{orgId}/tournaments/{tId}/divisions/{divId}/participants",
                            orgAId, tPubA, divPubA)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body)))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("他大会divisionの参加チームを自大会URLで更新しようとすると404（pId束縛）")
        void 他大会参加チームの越境更新は404() throws Exception {
            Long foreignParticipant = insertParticipant(divPrivB, teamAId);
            em.flush();

            setAuthentication(adminAId);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("seed", 2);

            mockMvc.perform(patch(
                            "/api/v1/organizations/{orgId}/tournaments/{tId}/divisions/{divId}/participants/{pId}",
                            orgAId, tPubA, divPubA, foreignParticipant)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("TOUR_018"));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // Fixture（節・対戦カード・スコア）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("対戦カード(fixture)・節(matchday)")
    class Fixture {

        @Test
        @DisplayName("他組織ADMINが自組織URLで他組織大会に節を作成しようとすると404（BOLA是正）")
        void 他組織ADMINによる越境節作成は404() throws Exception {
            setAuthentication(adminBId);

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("name", "TOUR92C乗っ取り節");

            mockMvc.perform(post(
                            "/api/v1/organizations/{orgId}/tournaments/{tId}/divisions/{divId}/matchdays",
                            orgBId, tPubA, divPubA)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("TOUR_001"));
        }

        @Test
        @DisplayName("非ADMINメンバーの節作成は403")
        void 非ADMINメンバーの節作成は403() throws Exception {
            setAuthentication(memberAId);

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("name", "TOUR92C第2節");

            mockMvc.perform(post(
                            "/api/v1/organizations/{orgId}/tournaments/{tId}/divisions/{divId}/matchdays",
                            orgAId, tPubA, divPubA)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body)))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINの節作成は201")
        void 正当ADMINの節作成は201() throws Exception {
            setAuthentication(adminAId);

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("name", "TOUR92C第2節");

            mockMvc.perform(post(
                            "/api/v1/organizations/{orgId}/tournaments/{tId}/divisions/{divId}/matchdays",
                            orgAId, tPubA, divPubA)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body)))
                    .andExpect(status().isCreated());
        }

        @Test
        @DisplayName("公開大会のtIdを踏み台に非公開大会divIdの節一覧を見ると404（divId束縛）")
        void 公開大会踏み台の非公開div節一覧は404() throws Exception {
            clearAuthentication();

            mockMvc.perform(get(
                            "/api/v1/organizations/{orgId}/tournaments/{tId}/divisions/{divId}/matchdays",
                            orgAId, tPubA, divPrivB))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("TOUR_002"));
        }

        @Test
        @DisplayName("公開大会のtIdを踏み台に非公開大会matchIdの試合詳細を見ると404（matchId束縛）")
        void 公開大会踏み台の非公開match詳細は404() throws Exception {
            clearAuthentication();

            mockMvc.perform(get("/api/v1/organizations/{orgId}/tournaments/{tId}/matches/{matchId}",
                            orgAId, tPubA, matchPrivB))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("TOUR_003"));
        }

        @Test
        @DisplayName("節一括スコアに他大会のmatchIdを混入させると404（entry束縛・書込BOLA遮断）")
        void 節一括スコアへの他大会matchId混入は404() throws Exception {
            setAuthentication(adminAId);

            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("matchId", matchPrivB);
            entry.put("homeScore", 3);
            entry.put("awayScore", 0);
            entry.put("version", 0);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("scores", List.of(entry));

            mockMvc.perform(post(
                            "/api/v1/organizations/{orgId}/tournaments/{tId}/divisions/{divId}/matchdays/{mdId}/scores/batch",
                            orgAId, tPubA, divPubA, matchdayPubA)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("TOUR_003"));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // Promotion（昇格・降格）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("昇降格(promotion)")
    class Promotion {

        @Test
        @DisplayName("非ADMINメンバーの昇降格実行は403")
        void 非ADMINメンバーの昇降格実行は403() throws Exception {
            setAuthentication(memberAId);

            mockMvc.perform(post("/api/v1/organizations/{orgId}/tournaments/{tId}/promotions", orgAId, tPubA)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(promotionBody(divPubA, divPubA))))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("他組織ADMINが自組織URLで他組織大会の昇降格を実行しようとすると404（BOLA是正）")
        void 他組織ADMINによる越境昇降格実行は404() throws Exception {
            setAuthentication(adminBId);

            mockMvc.perform(post("/api/v1/organizations/{orgId}/tournaments/{tId}/promotions", orgBId, tPubA)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(promotionBody(divPubA, divPubA))))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("TOUR_001"));
        }

        @Test
        @DisplayName("正当ADMINの昇降格実行は201")
        void 正当ADMINの昇降格実行は201() throws Exception {
            setAuthentication(adminAId);

            mockMvc.perform(post("/api/v1/organizations/{orgId}/tournaments/{tId}/promotions", orgAId, tPubA)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(promotionBody(divPubA, divPubA))))
                    .andExpect(status().isCreated());
        }

        @Test
        @DisplayName("entriesに他大会のdivisionIdを混入させると404（division束縛）")
        void entriesへの他大会division混入は404() throws Exception {
            setAuthentication(adminAId);

            mockMvc.perform(post("/api/v1/organizations/{orgId}/tournaments/{tId}/promotions", orgAId, tPubA)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(promotionBody(divPrivB, divPubA))))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("TOUR_002"));
        }

        @Test
        @DisplayName("非メンバーの非公開大会昇降格履歴は404（可視性 fail-closed）")
        void 非メンバーの非公開大会昇降格履歴は404() throws Exception {
            setAuthentication(outsiderId);

            mockMvc.perform(get("/api/v1/organizations/{orgId}/tournaments/{tId}/promotions", orgAId, tPrivA))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("非ADMINメンバーの昇降格プレビューは403")
        void 非ADMINメンバーの昇降格プレビューは403() throws Exception {
            setAuthentication(memberAId);

            mockMvc.perform(post("/api/v1/organizations/{orgId}/tournaments/{tId}/promotions/preview",
                            orgAId, tPubA))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINの昇降格プレビューは200")
        void 正当ADMINの昇降格プレビューは200() throws Exception {
            setAuthentication(adminAId);

            mockMvc.perform(post("/api/v1/organizations/{orgId}/tournaments/{tId}/promotions/preview",
                            orgAId, tPubA))
                    .andExpect(status().isOk());
        }

        private Map<String, Object> promotionBody(Long fromDivisionId, Long toDivisionId) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("teamId", teamAId);
            entry.put("fromDivisionId", fromDivisionId);
            entry.put("toDivisionId", toDivisionId);
            entry.put("type", "PROMOTION");
            entry.put("finalRank", 1);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("entries", List.of(entry));
            return body;
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // Template（大会テンプレート）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("大会テンプレート(template)")
    class Template {

        @Test
        @DisplayName("非メンバーのテンプレート一覧は403（COMMON_002）")
        void 非メンバーのテンプレート一覧は403() throws Exception {
            setAuthentication(outsiderId);

            mockMvc.perform(get("/api/v1/organizations/{orgId}/tournament-templates", orgAId))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("組織メンバーのテンプレート一覧は200")
        void 組織メンバーのテンプレート一覧は200() throws Exception {
            setAuthentication(memberAId);

            mockMvc.perform(get("/api/v1/organizations/{orgId}/tournament-templates", orgAId))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("他組織ADMINが自組織URLで他組織のテンプレート詳細を見ると404（org束縛・BOLA是正）")
        void 他組織ADMINによる越境テンプレート詳細は404() throws Exception {
            setAuthentication(adminBId);

            mockMvc.perform(get("/api/v1/organizations/{orgId}/tournament-templates/{templateId}",
                            orgBId, templateAId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("TOUR_013"));
        }

        @Test
        @DisplayName("非ADMINメンバーのテンプレート更新は403")
        void 非ADMINメンバーのテンプレート更新は403() throws Exception {
            setAuthentication(memberAId);

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("name", "TOUR92C乗っ取り改名");
            body.put("version", 0);

            mockMvc.perform(patch("/api/v1/organizations/{orgId}/tournament-templates/{templateId}",
                            orgAId, templateAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body)))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("他組織ADMINが自組織URLで他組織のテンプレートを削除しようとすると404（BOLA是正）")
        void 他組織ADMINによる越境テンプレート削除は404() throws Exception {
            setAuthentication(adminBId);

            mockMvc.perform(delete("/api/v1/organizations/{orgId}/tournament-templates/{templateId}",
                            orgBId, templateAId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("TOUR_013"));
        }

        @Test
        @DisplayName("正当ADMINのテンプレート作成は201・削除は204")
        void 正当ADMINのテンプレート作成と削除() throws Exception {
            setAuthentication(adminAId);

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("name", "TOUR92C新テンプレ");
            body.put("supportedFormats", "[\"LEAGUE\"]");

            mockMvc.perform(post("/api/v1/organizations/{orgId}/tournament-templates", orgAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body)))
                    .andExpect(status().isCreated());

            mockMvc.perform(delete("/api/v1/organizations/{orgId}/tournament-templates/{templateId}",
                            orgAId, templateAId))
                    .andExpect(status().isNoContent());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 公開API・埋め込みAPI（非公開 division 閲覧穴の閉塞）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("公開API(public)・埋め込みAPI(embed)")
    class PublicAndEmbed {

        @Test
        @DisplayName("公開大会の公開順位表は匿名で200（正当な公開経路は非破壊）")
        void 公開大会の公開順位表は匿名で200() throws Exception {
            clearAuthentication();

            mockMvc.perform(get(
                            "/api/v1/public/organizations/{orgId}/tournaments/{tId}/divisions/{divId}/standings",
                            orgAId, tPubA, divPubA))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("公開大会のtIdを踏み台に非公開大会divIdの公開順位表を見ると404（台帳指摘の穴）")
        void 公開大会踏み台の非公開div公開順位表は404() throws Exception {
            clearAuthentication();

            mockMvc.perform(get(
                            "/api/v1/public/organizations/{orgId}/tournaments/{tId}/divisions/{divId}/standings",
                            orgAId, tPubA, divPrivB))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("TOUR_002"));
        }

        @Test
        @DisplayName("公開大会のtIdを踏み台に非公開大会divIdの公開マトリクスを見ると404")
        void 公開大会踏み台の非公開div公開マトリクスは404() throws Exception {
            clearAuthentication();

            mockMvc.perform(get(
                            "/api/v1/public/organizations/{orgId}/tournaments/{tId}/divisions/{divId}/matrix",
                            orgAId, tPubA, divPrivB))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("TOUR_002"));
        }

        @Test
        @DisplayName("非公開大会の埋め込み順位表は匿名で404（embedの可視性欠落是正）")
        void 非公開大会の埋め込み順位表は匿名で404() throws Exception {
            clearAuthentication();

            mockMvc.perform(get("/api/v1/embed/organizations/{orgId}/tournaments/{tId}/standings/{divId}",
                            orgBId, tPrivB, divPrivB))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("公開大会の埋め込み順位表は匿名で200（正当な埋め込みは非破壊）")
        void 公開大会の埋め込み順位表は匿名で200() throws Exception {
            clearAuthentication();

            mockMvc.perform(get("/api/v1/embed/organizations/{orgId}/tournaments/{tId}/standings/{divId}",
                            orgAId, tPubA, divPubA))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // ヘルパー
    // ═════════════════════════════════════════════════════════════════════

    private void setAuthentication(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
    }

    private void clearAuthentication() {
        SecurityContextHolder.clearContext();
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
                                + "VALUES (:email, 'TourContract', 'テスト', 'TOUR92C契約テスト', 'ACTIVE', "
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
                                + "CONCAT('t92c-', LEFT(REPLACE(UUID(),'-',''),8)), NOW(), NOW())")
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
                                + "CONCAT('t92c-', LEFT(REPLACE(UUID(),'-',''),8)), NOW(), NOW())")
                .setParameter("name", name)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM teams WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult()).longValue();
    }

    /**
     * 大会を seed する。entity の NOT NULL 列（@Builder.Default は DDL デフォルトを生成しない）を
     * すべて明示指定する（TournamentEntity ⇔ DDL 突合済み）。
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

    private Long insertMatchday(Long divisionId, String name) {
        em.createNativeQuery(
                        "INSERT INTO tournament_matchdays (division_id, name, matchday_number, status, "
                                + "created_at, updated_at) "
                                + "VALUES (:divId, :name, 1, 'SCHEDULED', NOW(), NOW())")
                .setParameter("divId", divisionId)
                .setParameter("name", name)
                .executeUpdate();
        return ((Number) em.createNativeQuery(
                        "SELECT MAX(id) FROM tournament_matchdays WHERE division_id = :divId")
                .setParameter("divId", divisionId)
                .getSingleResult()).longValue();
    }

    private Long insertFixture(Long matchdayId) {
        em.createNativeQuery(
                        "INSERT INTO tournament_matches (matchday_id, match_number, result, leg, status, "
                                + "version, created_at, updated_at) "
                                + "VALUES (:mdId, 1, 'PENDING', 1, 'SCHEDULED', 0, NOW(), NOW())")
                .setParameter("mdId", matchdayId)
                .executeUpdate();
        return ((Number) em.createNativeQuery(
                        "SELECT MAX(id) FROM tournament_matches WHERE matchday_id = :mdId")
                .setParameter("mdId", matchdayId)
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

    private Long insertTemplate(Long orgId, String name, Long createdBy) {
        em.createNativeQuery(
                        "INSERT INTO tournament_templates (organization_id, name, supported_formats, "
                                + "win_points, draw_points, loss_points, has_draw, has_sets, "
                                + "has_extra_time, has_penalties, score_unit_label, version, created_by, "
                                + "created_at, updated_at) "
                                + "VALUES (:orgId, :name, '[\"LEAGUE\"]', "
                                + "3, 1, 0, 1, 0, "
                                + "0, 0, '点', 0, :createdBy, NOW(), NOW())")
                .setParameter("orgId", orgId)
                .setParameter("name", name)
                .setParameter("createdBy", createdBy)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM tournament_templates WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult()).longValue();
    }
}
