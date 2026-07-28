package com.mannschaft.app.tournament;

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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 認可根治戦役 Wave7 — 大会 PDF 出力／大会一覧・詳細の可視性契約テスト。
 *
 * <p><b>PDF 経路の可視性判定</b>: {@code TournamentPdfController} の 4 本
 * （順位表 / トーナメント表 / 個人ランキング / 対戦マトリクス）は、<b>同一データを JSON で返す</b>
 * {@code StandingsController} が冒頭で呼んでいる {@code verifyTournamentVisible(tId)} と
 * 同一の可視性ガードを通す。パスも {@code .../standings} と {@code .../standings/pdf} という
 * 兄弟関係にあり、両者が常に同じ結果になることを本テストで担保する。加えて
 * {@code findTournamentOrThrow} でパス {@code orgId} と大会実体 {@code organizationId} の
 * 突合も行う。</p>
 *
 * <p><b>大会一覧・詳細の可視性判定</b>: {@code TournamentController} の
 * {@code listTournaments} / {@code getTournament} は org 突合のうえで F00 共通可視性を適用し、
 * 閲覧者に見える大会のみ（主催組織 ADMIN/DEPUTY_ADMIN は DRAFT を含む自組織の全大会）を返す。</p>
 *
 * <p><b>本テストの主眼</b>: JSON 版と PDF 版で<b>同じ可視性判定になる</b>ことを、
 * 同一の認証主体・同一の大会に対して両方を叩いて突き合わせることで機械的に保証する。</p>
 *
 * <p>金型: {@code TournamentScopeContractIT}
 * （{@code @AutoConfigureMockMvc(addFilters=false)} + 実 MySQL + 手動 SecurityContext）。
 * 認可フィルタ無効でも、越境 403/404 はアプリ層例外として検証できる。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("tournament PDF・大会読取 可視性契約テスト（Wave7）")
class TournamentPdfScopeContractIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @PersistenceContext
    private EntityManager em;

    private Long orgAId;
    private Long orgBId;

    /** ORG A の ADMIN（主催者・正当） */
    private Long orgAdminAId;
    /** ORG A の一般メンバー */
    private Long memberAId;
    /** ORG B の ADMIN（別 scope からの越境検証用） */
    private Long orgAdminBId;
    /** どこにも所属しない非メンバー */
    private Long outsiderId;

    /** 公開大会（orgA・PUBLIC×OPEN） */
    private Long tPubA;
    /** 非公開大会（orgA・MEMBERS_AND_ABOVE×OPEN） */
    private Long tPrivA;
    /** DRAFT 大会（orgA・PUBLIC×DRAFT・作成者は orgAdminA） */
    private Long tDraftA;
    /** 非公開大会（orgB・MEMBERS_AND_ABOVE×OPEN・被害者スコープ） */
    private Long tPrivB;

    private Long divPubA;
    private Long divPrivA;
    private Long divPrivB;

    @BeforeEach
    void setUp() {
        insertRoleIfAbsent("ADMIN", "管理者", 2);
        insertRoleIfAbsent("MEMBER", "メンバー", 4);

        orgAId = insertOrganization("W7PDF組織A");
        orgBId = insertOrganization("W7PDF組織B");

        orgAdminAId = insertUser("w7pdf-orgadmin-a@example.com");
        memberAId = insertUser("w7pdf-member-a@example.com");
        orgAdminBId = insertUser("w7pdf-orgadmin-b@example.com");
        outsiderId = insertUser("w7pdf-outsider@example.com");

        MembershipTestHelper.insertUserRole(em, orgAdminAId, "ADMIN", null, orgAId);
        MembershipTestHelper.insertMembership(em, orgAdminAId, ScopeType.ORGANIZATION, orgAId, RoleKind.MEMBER);
        MembershipTestHelper.insertMembership(em, memberAId, ScopeType.ORGANIZATION, orgAId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, orgAdminBId, "ADMIN", null, orgBId);
        MembershipTestHelper.insertMembership(em, orgAdminBId, ScopeType.ORGANIZATION, orgBId, RoleKind.MEMBER);

        tPubA = insertTournament(orgAId, "W7PDF公開大会A", "PUBLIC", "OPEN", orgAdminAId);
        tPrivA = insertTournament(orgAId, "W7PDF非公開大会A", "MEMBERS_AND_ABOVE", "OPEN", orgAdminAId);
        tDraftA = insertTournament(orgAId, "W7PDF下書大会A", "PUBLIC", "DRAFT", orgAdminAId);
        tPrivB = insertTournament(orgBId, "W7PDF非公開大会B", "MEMBERS_AND_ABOVE", "OPEN", orgAdminBId);

        divPubA = insertDivision(tPubA, "W7PDF公開A1部");
        divPrivA = insertDivision(tPrivA, "W7PDF非公開A1部");
        divPrivB = insertDivision(tPrivB, "W7PDF非公開B1部");

        em.flush();
        em.clear();
    }

    // ═════════════════════════════════════════════════════════════════════
    // 1. 非公開大会の PDF アクセス制御
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("1. 非公開大会のPDFは取得できない")
    class PrivateTournamentPdfBlocked {

        @Test
        @DisplayName("非メンバーは他組織の非公開大会の順位表PDFを取得できない（404）")
        void 非メンバーは順位表PDF404() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get(STANDINGS_PDF, orgBId, tPrivB, divPrivB))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("非メンバーは他組織の非公開大会のトーナメント表PDFを取得できない（404）")
        void 非メンバーはブラケットPDF404() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get(BRACKET_PDF, orgBId, tPrivB))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("非メンバーは他組織の非公開大会の個人ランキングPDFを取得できない（404）")
        void 非メンバーはランキングPDF404() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get(RANKINGS_PDF, orgBId, tPrivB, "goals"))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("非メンバーは他組織の非公開大会の対戦マトリクスPDFを取得できない（404）")
        void 非メンバーはマトリクスPDF404() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get(MATRIX_PDF, orgBId, tPrivB, divPrivB))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("別scope ADMIN（orgBのADMIN）はorgAの非公開大会PDFを取得できない（404・BOLA）")
        void 別scopeADMINは404() throws Exception {
            setAuth(orgAdminBId);
            mockMvc.perform(get(STANDINGS_PDF, orgAId, tPrivA, divPrivA))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("DRAFT大会のPDFは非メンバーには取得できない（404）")
        void DRAFT大会PDFは404() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get(BRACKET_PDF, orgAId, tDraftA))
                    .andExpect(status().isNotFound());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 2. JSON 版と PDF 版で同じ可視性判定になること（兄弟対照）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("2. JSON版とPDF版の可視性判定が一致する")
    class JsonPdfParity {

        @Test
        @DisplayName("非メンバー: 非公開大会の順位表は JSON も PDF も 404")
        void 非公開は両方404() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get(STANDINGS_JSON, orgBId, tPrivB, divPrivB))
                    .andExpect(status().isNotFound());
            mockMvc.perform(get(STANDINGS_PDF, orgBId, tPrivB, divPrivB))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("非メンバー: 公開大会の順位表は JSON も PDF も 200")
        void 公開は両方200() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get(STANDINGS_JSON, orgAId, tPubA, divPubA))
                    .andExpect(status().isOk());
            mockMvc.perform(get(STANDINGS_PDF, orgAId, tPubA, divPubA))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("組織メンバー: 自組織の非公開大会は JSON も PDF も 200")
        void メンバーは非公開も両方200() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(get(STANDINGS_JSON, orgAId, tPrivA, divPrivA))
                    .andExpect(status().isOk());
            mockMvc.perform(get(STANDINGS_PDF, orgAId, tPrivA, divPrivA))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("非メンバー: 対戦マトリクスも JSON / PDF で判定が一致する")
        void マトリクスも一致() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get(MATRIX_JSON, orgBId, tPrivB, divPrivB))
                    .andExpect(status().isNotFound());
            mockMvc.perform(get(MATRIX_PDF, orgBId, tPrivB, divPrivB))
                    .andExpect(status().isNotFound());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 3. パス orgId と大会実体 organizationId の突合（PDF の org 束縛欠落）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("3. パスorgIdと大会のorganizationId突合")
    class OrgBinding {

        @Test
        @DisplayName("自組織パスに他組織の公開大会IDを混ぜても404（存在秘匿）")
        void 他組織大会IDは404() throws Exception {
            setAuth(orgAdminAId);
            // tPrivB は orgB の大会。orgA のパスに混ぜても通してはならない。
            mockMvc.perform(get(BRACKET_PDF, orgAId, tPrivB))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("可視な公開大会でもパスorgIdが違えば404")
        void 公開大会でもorg不一致は404() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get(BRACKET_PDF, orgBId, tPubA))
                    .andExpect(status().isNotFound());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 4. 大会詳細 GET（可視性＋org束縛）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("4. GET 大会詳細（可視性＋org束縛）")
    class GetTournamentDetail {

        @Test
        @DisplayName("非メンバーは他組織の非公開大会の詳細を取得できない（404）")
        void 非メンバーは非公開404() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get(TOURNAMENT_DETAIL, orgBId, tPrivB))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("別scope ADMINはorgAの非公開大会の詳細を取得できない（404・BOLA）")
        void 別scopeADMINは404() throws Exception {
            setAuth(orgAdminBId);
            mockMvc.perform(get(TOURNAMENT_DETAIL, orgAId, tPrivA))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("非メンバーは他組織のDRAFT大会の詳細を取得できない（404）")
        void 非メンバーはDRAFT404() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get(TOURNAMENT_DETAIL, orgAId, tDraftA))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("パスorgIdと大会のorganizationIdが不一致なら404（存在秘匿）")
        void org不一致は404() throws Exception {
            setAuth(orgAdminAId);
            mockMvc.perform(get(TOURNAMENT_DETAIL, orgAId, tPrivB))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("非メンバーでも公開大会の詳細は200（正常系・非破壊）")
        void 公開大会は200() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get(TOURNAMENT_DETAIL, orgAId, tPubA))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("組織メンバーは自組織の非公開大会の詳細を取得できる（200）")
        void メンバーは非公開200() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(get(TOURNAMENT_DETAIL, orgAId, tPrivA))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("主催組織ADMINは自組織のDRAFT大会の詳細を取得できる（管理画面の非退行）")
        void 主催組織ADMINはDRAFT200() throws Exception {
            setAuth(orgAdminAId);
            mockMvc.perform(get(TOURNAMENT_DETAIL, orgAId, tDraftA))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 5. 大会一覧 GET（可視性フィルタ）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("5. GET 大会一覧（可視性フィルタ）")
    class ListTournaments {

        @Test
        @DisplayName("非メンバーには他組織の非公開大会が一覧に出ない（0件）")
        void 非メンバーには非公開が出ない() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get(TOURNAMENT_LIST, orgBId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(0));
        }

        @Test
        @DisplayName("別scope ADMINにもorgAの非公開・DRAFT大会は出ず、公開大会のみ（1件）")
        void 別scopeADMINには公開のみ() throws Exception {
            setAuth(orgAdminBId);
            mockMvc.perform(get(TOURNAMENT_LIST, orgAId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(1));
        }

        @Test
        @DisplayName("非メンバーにはorgAの公開大会のみ見える（1件・DRAFTと非公開は除外）")
        void 非メンバーには公開のみ() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get(TOURNAMENT_LIST, orgAId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(1));
        }

        @Test
        @DisplayName("組織メンバーには公開＋非公開が見える（2件・DRAFTは除外）")
        void メンバーには公開と非公開() throws Exception {
            setAuth(memberAId);
            mockMvc.perform(get(TOURNAMENT_LIST, orgAId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(2));
        }

        @Test
        @DisplayName("主催組織ADMINにはDRAFTを含む全3件が見える（管理画面の非退行）")
        void 主催組織ADMINには全件() throws Exception {
            setAuth(orgAdminAId);
            mockMvc.perform(get(TOURNAMENT_LIST, orgAId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(3));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // ヘルパー
    // ═════════════════════════════════════════════════════════════════════

    private static final String STANDINGS_PDF =
            "/api/v1/organizations/{orgId}/tournaments/{tId}/divisions/{divId}/standings/pdf";
    private static final String STANDINGS_JSON =
            "/api/v1/organizations/{orgId}/tournaments/{tId}/divisions/{divId}/standings";
    private static final String MATRIX_PDF =
            "/api/v1/organizations/{orgId}/tournaments/{tId}/divisions/{divId}/matrix/pdf";
    private static final String MATRIX_JSON =
            "/api/v1/organizations/{orgId}/tournaments/{tId}/divisions/{divId}/matrix";
    private static final String BRACKET_PDF =
            "/api/v1/organizations/{orgId}/tournaments/{tId}/bracket/pdf";
    private static final String RANKINGS_PDF =
            "/api/v1/organizations/{orgId}/tournaments/{tId}/rankings/{statKey}/pdf";
    private static final String TOURNAMENT_DETAIL =
            "/api/v1/organizations/{orgId}/tournaments/{tId}";
    private static final String TOURNAMENT_LIST =
            "/api/v1/organizations/{orgId}/tournaments";

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
                                + "VALUES (:email, 'W7Pdf', 'テスト', 'W7PDF契約テスト', 'ACTIVE', "
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
                                + "CONCAT('w7p-', LEFT(REPLACE(UUID(),'-',''),8)), NOW(), NOW())")
                .setParameter("name", name)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM organizations WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult()).longValue();
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
}
