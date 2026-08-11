package com.mannschaft.app.schedule;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.schedule.entity.ScheduleKeepEntity;
import com.mannschaft.app.schedule.entity.ScheduleKeepStatus;
import com.mannschaft.app.schedule.repository.ScheduleKeepRepository;
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

import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F03.17 キープ（日付未定の予定）チームスコープ API 契約テスト（試練 Wave1）。
 *
 * <p>設計書: {@code docs/features/F03.17_schedule_keep.md} §9 受け入れ条件。
 * 本クラスは Wave1 の担当 AC のうち、{@code addFilters = false} ＋
 * {@link SecurityContextHolder} 直接差し替えで検証できるものを対象とする
 * （金型: {@code ScheduleAuthzScopeContractIT}）。「未ログインは 401」（AC-16b の一部）は
 * 実フィルタチェーンを要するため {@link ScheduleKeepUnauthenticatedContractIT} に分離した。</p>
 *
 * <p>プロダクションコード（Controller/Service/DTO）は本試練の時点で未実装のため、
 * 全リクエストは現状ハンドラ未存在の 404 を返す。これは意図した red 状態である。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("F03.17 キープ チームスコープ API 契約テスト（試練 Wave1）")
class ScheduleKeepTeamContractIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ScheduleKeepRepository scheduleKeepRepository;

    @PersistenceContext
    private EntityManager em;

    private Long teamId;
    private Long otherTeamId;
    private String teamSlug;
    private String otherTeamSlug;

    /** チームの一般メンバー（キープ作成者を兼ねる）。 */
    private Long memberId;
    /** チームの ADMIN。 */
    private Long adminId;
    /** チームの一般メンバーだが、作成者でも ADMIN でもない者（権限検証用）。 */
    private Long otherMemberId;
    /** チームの SUPPORTER（応援者）。 */
    private Long supporterId;
    /** どこにも所属しない利用者（GUEST 相当。IDOR 検証にも使う）。 */
    private Long outsiderId;
    /** 別チームの MEMBER（IDOR 検証用）。 */
    private Long otherTeamMemberId;

    @BeforeEach
    void setUp() {
        // teams.slug は length=30（TeamEntity）。"keep-other-team-" + nanoTime(19桁) は
        // 36桁で列長超過し MySQL の Data truncation で INSERT が落ちる（試練の既知バグ）。
        // 短い接頭辞 + nanoTime を6桁に丸めたサフィックスで衝突を避けつつ列長に収める。
        long suffix = System.nanoTime() % 1_000_000L;
        teamSlug = "kt-" + suffix;
        otherTeamSlug = "kt2-" + suffix;
        teamId = insertTeam("キープ試練チーム", teamSlug);
        otherTeamId = insertTeam("キープ試練別チーム", otherTeamSlug);

        memberId = insertUser("keep-member@example.com");
        adminId = insertUser("keep-admin@example.com");
        otherMemberId = insertUser("keep-other-member@example.com");
        supporterId = insertUser("keep-supporter@example.com");
        outsiderId = insertUser("keep-outsider@example.com");
        otherTeamMemberId = insertUser("keep-other-team-member@example.com");

        MembershipTestHelper.insertMembership(em, memberId, ScopeType.TEAM, teamId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, memberId, "MEMBER", teamId, null);

        MembershipTestHelper.insertMembership(em, adminId, ScopeType.TEAM, teamId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminId, "ADMIN", teamId, null);

        MembershipTestHelper.insertMembership(em, otherMemberId, ScopeType.TEAM, teamId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, otherMemberId, "MEMBER", teamId, null);

        MembershipTestHelper.insertMembership(em, supporterId, ScopeType.TEAM, teamId, RoleKind.SUPPORTER);
        MembershipTestHelper.insertUserRole(em, supporterId, "SUPPORTER", teamId, null);

        MembershipTestHelper.insertMembership(em, otherTeamMemberId, ScopeType.TEAM, otherTeamId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, otherTeamMemberId, "MEMBER", otherTeamId, null);

        // outsiderId はどこにも所属させない（GUEST 相当）。

        em.flush();
        em.clear();
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-01 / AC-02 / AC-02b / AC-02c / AC-03（作成・一覧の基本契約）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("作成・一覧の基本契約")
    class CreateAndList {

        @Test
        @DisplayName("AC-01: タイトル1項目だけのPOSTは201で通り、メモ・候補日を空のまま送っても400にならない")
        void AC01_タイトルのみのPOSTは201() throws Exception {
            setAuthentication(memberId);
            mockMvc.perform(post("/api/v1/teams/{teamPublicId}/schedule-keeps", teamSlug)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("title", "夏合宿"))))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.title").value("夏合宿"))
                    .andExpect(jsonPath("$.data.status").value("KEPT"))
                    .andExpect(jsonPath("$.data.memo").doesNotExist());
        }

        @Test
        @DisplayName("AC-02: memoとcandidateDates（3件）で作成すると、GETで昇順ソート・重複除去されて返る")
        void AC02_候補日は昇順ソート重複除去される() throws Exception {
            setAuthentication(memberId);
            mockMvc.perform(post("/api/v1/teams/{teamPublicId}/schedule-keeps", teamSlug)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "title", "秋合宿",
                                    "memo", "海の近くがいいという声あり",
                                    "candidateDates", List.of(
                                            "2026-09-19", "2026-09-12", "2026-09-19")))))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.candidateDates[0]").value("2026-09-12"))
                    .andExpect(jsonPath("$.data.candidateDates[1]").value("2026-09-19"))
                    .andExpect(jsonPath("$.data.candidateDates.length()").value(2));
        }

        @Test
        @DisplayName("AC-02b: candidateDatesに[]を送るとDBにはNULLが入り、GETレスポンスもnull（空配列ではない）")
        void AC02b_空配列はDBにNULLで格納されGETもnull() throws Exception {
            setAuthentication(memberId);
            String body = mockMvc.perform(post("/api/v1/teams/{teamPublicId}/schedule-keeps", teamSlug)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "title", "候補日なしキープ",
                                    "candidateDates", List.of()))))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.candidateDates").doesNotExist())
                    .andReturn().getResponse().getContentAsString();

            em.flush();
            em.clear();
            @SuppressWarnings("unchecked")
            String id = (String) ((Map<String, Object>) objectMapper.readValue(body, Map.class).get("data")).get("id");
            ScheduleKeepEntity saved = scheduleKeepRepository.findById(java.util.UUID.fromString(id)).orElseThrow();
            org.assertj.core.api.Assertions.assertThat(saved.getCandidateDates()).isNull();
        }

        @Test
        @DisplayName("AC-02c: 過去日（2020-01-01）を候補日に含めても201（弾かれない）")
        void AC02c_過去日は弾かれない() throws Exception {
            setAuthentication(memberId);
            mockMvc.perform(post("/api/v1/teams/{teamPublicId}/schedule-keeps", teamSlug)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "title", "過去日を含むキープ",
                                    "candidateDates", List.of("2020-01-01")))))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.candidateDates[0]").value("2020-01-01"));
        }

        @Test
        @DisplayName("AC-03: 一覧GETは既定でstatus=KEPTのみを返し、sort_order昇順→created_at降順で並ぶ。"
                + "status=ALLで他状態も含まれる")
        void AC03_一覧は既定KEPTのみ_sortOrder昇順_createdAt降順() throws Exception {
            ScheduleKeepEntity kept1 = saveKeep(teamId, memberId, "先に作ったKEPT", ScheduleKeepStatus.KEPT, 0);
            ScheduleKeepEntity kept2 = saveKeep(teamId, memberId, "後で作ったKEPT", ScheduleKeepStatus.KEPT, 0);
            saveKeep(teamId, memberId, "アーカイブ済み", ScheduleKeepStatus.ARCHIVED, 0);
            em.flush();
            em.clear();

            setAuthentication(memberId);
            mockMvc.perform(get("/api/v1/teams/{teamPublicId}/schedule-keeps", teamSlug))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(2))
                    .andExpect(jsonPath("$.data[0].id").value(kept2.getId().toString()))
                    .andExpect(jsonPath("$.data[1].id").value(kept1.getId().toString()));

            mockMvc.perform(get("/api/v1/teams/{teamPublicId}/schedule-keeps", teamSlug)
                            .param("status", "ALL"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(3));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-12 / AC-13 / AC-13b / AC-21 / AC-22 / AC-23（入力検証・境界値）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("入力検証・境界値")
    class Validation {

        @Test
        @DisplayName("AC-12: title=\"\" は400 SCHEDULE_KEEP_002")
        void AC12_空文字titleは400() throws Exception {
            setAuthentication(memberId);
            mockMvc.perform(post("/api/v1/teams/{teamPublicId}/schedule-keeps", teamSlug)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("title", ""))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("SCHEDULE_KEEP_002"));
        }

        @Test
        @DisplayName("AC-12: title=\"   \"（空白のみ）は400 SCHEDULE_KEEP_002")
        void AC12_空白のみtitleは400() throws Exception {
            setAuthentication(memberId);
            mockMvc.perform(post("/api/v1/teams/{teamPublicId}/schedule-keeps", teamSlug)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("title", "   "))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("SCHEDULE_KEEP_002"));
        }

        @Test
        @DisplayName("AC-13: candidateDates 11件は400 SCHEDULE_KEEP_003")
        void AC13_候補日11件は400() throws Exception {
            List<String> elevenDates = List.of(
                    "2026-01-01", "2026-01-02", "2026-01-03", "2026-01-04", "2026-01-05",
                    "2026-01-06", "2026-01-07", "2026-01-08", "2026-01-09", "2026-01-10",
                    "2026-01-11");
            setAuthentication(memberId);
            mockMvc.perform(post("/api/v1/teams/{teamPublicId}/schedule-keeps", teamSlug)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "title", "候補日過多", "candidateDates", elevenDates))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("SCHEDULE_KEEP_003"));
        }

        @Test
        @DisplayName("AC-13b: candidateDatesに\"2026/08/15\"（区切り文字不正）は400 SCHEDULE_KEEP_004（003とは別コード）")
        void AC13b_スラッシュ区切りは400_004() throws Exception {
            setAuthentication(memberId);
            mockMvc.perform(post("/api/v1/teams/{teamPublicId}/schedule-keeps", teamSlug)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "title", "不正日付形式", "candidateDates", List.of("2026/08/15")))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("SCHEDULE_KEEP_004"));
        }

        @Test
        @DisplayName("AC-13b: candidateDatesに\"2026-13-01\"（存在しない月）は400 SCHEDULE_KEEP_004（003とは別コード）")
        void AC13b_存在しない月は400_004() throws Exception {
            setAuthentication(memberId);
            mockMvc.perform(post("/api/v1/teams/{teamPublicId}/schedule-keeps", teamSlug)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "title", "不正日付形式2", "candidateDates", List.of("2026-13-01")))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("SCHEDULE_KEEP_004"));
        }

        @Test
        @DisplayName("AC-21: title 200文字ちょうどは201")
        void AC21_title200文字ちょうどは201() throws Exception {
            String title200 = "あ".repeat(200);
            setAuthentication(memberId);
            mockMvc.perform(post("/api/v1/teams/{teamPublicId}/schedule-keeps", teamSlug)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("title", title200))))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.title").value(title200));
        }

        @Test
        @DisplayName("AC-21: title 201文字は400 SCHEDULE_KEEP_002")
        void AC21_title201文字は400() throws Exception {
            String title201 = "あ".repeat(201);
            setAuthentication(memberId);
            mockMvc.perform(post("/api/v1/teams/{teamPublicId}/schedule-keeps", teamSlug)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("title", title201))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("SCHEDULE_KEEP_002"));
        }

        @Test
        @DisplayName("AC-22: candidateDates 10件ちょうどは201")
        void AC22_候補日10件ちょうどは201() throws Exception {
            List<String> tenDates = List.of(
                    "2026-01-01", "2026-01-02", "2026-01-03", "2026-01-04", "2026-01-05",
                    "2026-01-06", "2026-01-07", "2026-01-08", "2026-01-09", "2026-01-10");
            setAuthentication(memberId);
            mockMvc.perform(post("/api/v1/teams/{teamPublicId}/schedule-keeps", teamSlug)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "title", "候補日上限ちょうど", "candidateDates", tenDates))))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.candidateDates.length()").value(10));
        }

        @Test
        @DisplayName("AC-22: candidateDates 11件は400 SCHEDULE_KEEP_003（AC-13と対）")
        void AC22_候補日11件は400() throws Exception {
            List<String> elevenDates = List.of(
                    "2026-01-01", "2026-01-02", "2026-01-03", "2026-01-04", "2026-01-05",
                    "2026-01-06", "2026-01-07", "2026-01-08", "2026-01-09", "2026-01-10",
                    "2026-01-11");
            setAuthentication(memberId);
            mockMvc.perform(post("/api/v1/teams/{teamPublicId}/schedule-keeps", teamSlug)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "title", "候補日上限超過", "candidateDates", elevenDates))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("SCHEDULE_KEEP_003"));
        }

        @Test
        @DisplayName("AC-23: candidateDatesに[]を送ると保存されGETはnull（AC-02bと対）")
        void AC23_空配列は保存されGETはnull() throws Exception {
            setAuthentication(memberId);
            String body = mockMvc.perform(post("/api/v1/teams/{teamPublicId}/schedule-keeps", teamSlug)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "title", "AC23キープ", "candidateDates", List.of()))))
                    .andExpect(status().isCreated())
                    .andReturn().getResponse().getContentAsString();

            @SuppressWarnings("unchecked")
            String id = (String) ((Map<String, Object>) objectMapper.readValue(body, Map.class).get("data")).get("id");

            mockMvc.perform(get("/api/v1/teams/{teamPublicId}/schedule-keeps/{keepId}", teamSlug, id))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.candidateDates").doesNotExist());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-14a / AC-14b（IDOR・他チーム／スコープ跨ぎ）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("IDOR防御（他スコープからのアクセス）")
    class Idor {

        @Test
        @DisplayName("AC-14a: 別チームのMEMBERが単体GETすると404 SCHEDULE_KEEP_001（403ではない）")
        void AC14a_別チームMEMBERの単体GETは404() throws Exception {
            ScheduleKeepEntity keep = saveKeep(teamId, memberId, "他チームから見えないはず", ScheduleKeepStatus.KEPT, 0);
            em.flush();
            em.clear();

            setAuthentication(otherTeamMemberId);
            mockMvc.perform(get("/api/v1/teams/{teamPublicId}/schedule-keeps/{keepId}",
                            otherTeamSlug, keep.getId()))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("SCHEDULE_KEEP_001"));
        }

        @Test
        @DisplayName("AC-14b: 別チームのMEMBERがPATCHすると404")
        void AC14b_別チームMEMBERのPATCHは404() throws Exception {
            ScheduleKeepEntity keep = saveKeep(teamId, memberId, "他チームから編集できないはず", ScheduleKeepStatus.KEPT, 0);
            em.flush();
            em.clear();

            setAuthentication(otherTeamMemberId);
            mockMvc.perform(patch("/api/v1/teams/{teamPublicId}/schedule-keeps/{keepId}",
                            otherTeamSlug, keep.getId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("title", "乗っ取り"))))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("SCHEDULE_KEEP_001"));

            em.flush();
            em.clear();
            org.assertj.core.api.Assertions.assertThat(
                            scheduleKeepRepository.findById(keep.getId()).orElseThrow().getTitle())
                    .isEqualTo("他チームから編集できないはず");
        }

        @Test
        @DisplayName("AC-14b: 別チームのMEMBERがDELETEすると404")
        void AC14b_別チームMEMBERのDELETEは404() throws Exception {
            ScheduleKeepEntity keep = saveKeep(teamId, memberId, "他チームから削除できないはず", ScheduleKeepStatus.KEPT, 0);
            em.flush();
            em.clear();

            setAuthentication(otherTeamMemberId);
            mockMvc.perform(delete("/api/v1/teams/{teamPublicId}/schedule-keeps/{keepId}",
                            otherTeamSlug, keep.getId()))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("SCHEDULE_KEEP_001"));

            em.flush();
            em.clear();
            org.assertj.core.api.Assertions.assertThat(
                            scheduleKeepRepository.findById(keep.getId()).orElseThrow().getDeletedAt())
                    .isNull();
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-15（権限・編集系は作成者/ADMINのみ）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("権限（編集系は作成者/ADMINのみ）")
    class Permission {

        @Test
        @DisplayName("AC-15: 作成者でもADMINでもないMEMBERのPATCHは403 SCHEDULE_KEEP_005")
        void AC15_非作成者非ADMINのPATCHは403() throws Exception {
            ScheduleKeepEntity keep = saveKeep(teamId, memberId, "作成者のみ編集可", ScheduleKeepStatus.KEPT, 0);
            em.flush();
            em.clear();

            setAuthentication(otherMemberId);
            mockMvc.perform(patch("/api/v1/teams/{teamPublicId}/schedule-keeps/{keepId}", teamSlug, keep.getId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("title", "改ざん"))))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("SCHEDULE_KEEP_005"));
        }

        @Test
        @DisplayName("AC-15: 作成者でもADMINでもないMEMBERのDELETEは403 SCHEDULE_KEEP_005")
        void AC15_非作成者非ADMINのDELETEは403() throws Exception {
            ScheduleKeepEntity keep = saveKeep(teamId, memberId, "作成者のみ削除可", ScheduleKeepStatus.KEPT, 0);
            em.flush();
            em.clear();

            setAuthentication(otherMemberId);
            mockMvc.perform(delete("/api/v1/teams/{teamPublicId}/schedule-keeps/{keepId}", teamSlug, keep.getId()))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("SCHEDULE_KEEP_005"));
        }

        @Test
        @DisplayName("AC-15: 作成者でもADMINでもないMEMBERのrevertは403 SCHEDULE_KEEP_005")
        void AC15_非作成者非ADMINのrevertは403() throws Exception {
            ScheduleKeepEntity keep = saveKeep(teamId, memberId, "作成者のみrevert可", ScheduleKeepStatus.KEPT, 0);
            em.flush();
            em.clear();

            setAuthentication(otherMemberId);
            mockMvc.perform(post("/api/v1/teams/{teamPublicId}/schedule-keeps/{keepId}/revert",
                            teamSlug, keep.getId()))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("SCHEDULE_KEEP_005"));
        }

        @Test
        @DisplayName("AC-15: 作成者でもADMINでもないMEMBERのarchiveは403 SCHEDULE_KEEP_005")
        void AC15_非作成者非ADMINのarchiveは403() throws Exception {
            ScheduleKeepEntity keep = saveKeep(teamId, memberId, "作成者のみarchive可", ScheduleKeepStatus.KEPT, 0);
            em.flush();
            em.clear();

            setAuthentication(otherMemberId);
            mockMvc.perform(post("/api/v1/teams/{teamPublicId}/schedule-keeps/{keepId}/archive",
                            teamSlug, keep.getId()))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("SCHEDULE_KEEP_005"));
        }

        @Test
        @DisplayName("AC-15: 作成者でもADMINでもないMEMBERのrestoreは403 SCHEDULE_KEEP_005")
        void AC15_非作成者非ADMINのrestoreは403() throws Exception {
            ScheduleKeepEntity keep = saveKeep(teamId, memberId, "作成者のみrestore可", ScheduleKeepStatus.ARCHIVED, 0);
            em.flush();
            em.clear();

            setAuthentication(otherMemberId);
            mockMvc.perform(post("/api/v1/teams/{teamPublicId}/schedule-keeps/{keepId}/restore",
                            teamSlug, keep.getId()))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("SCHEDULE_KEEP_005"));
        }

        @Test
        @DisplayName("AC-15: 作成者でもADMINでもないMEMBERでもGETは200")
        void AC15_非作成者非ADMINでもGETは200() throws Exception {
            ScheduleKeepEntity keep = saveKeep(teamId, memberId, "誰でも閲覧可", ScheduleKeepStatus.KEPT, 0);
            em.flush();
            em.clear();

            setAuthentication(otherMemberId);
            mockMvc.perform(get("/api/v1/teams/{teamPublicId}/schedule-keeps/{keepId}", teamSlug, keep.getId()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.title").value("誰でも閲覧可"));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-16 / AC-16b（SUPPORTER・GUEST の遮断）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("SUPPORTER・GUESTの遮断（Wave1必須red）")
    class SupporterAndGuest {

        @Test
        @DisplayName("AC-16: チームSUPPORTERのキープ一覧GETは404")
        void AC16_SUPPORTERの一覧GETは404() throws Exception {
            saveKeep(teamId, memberId, "SUPPORTERには見えないはず", ScheduleKeepStatus.KEPT, 0);
            em.flush();
            em.clear();

            setAuthentication(supporterId);
            mockMvc.perform(get("/api/v1/teams/{teamPublicId}/schedule-keeps", teamSlug))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("SCHEDULE_KEEP_001"));
        }

        @Test
        @DisplayName("AC-16: チームSUPPORTERの単体GETは404")
        void AC16_SUPPORTERの単体GETは404() throws Exception {
            ScheduleKeepEntity keep = saveKeep(teamId, memberId, "SUPPORTERには見えないはず単体", ScheduleKeepStatus.KEPT, 0);
            em.flush();
            em.clear();

            setAuthentication(supporterId);
            mockMvc.perform(get("/api/v1/teams/{teamPublicId}/schedule-keeps/{keepId}", teamSlug, keep.getId()))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("SCHEDULE_KEEP_001"));
        }

        @Test
        @DisplayName("AC-16: チームSUPPORTERのキープ作成POSTは404")
        void AC16_SUPPORTERの作成POSTは404() throws Exception {
            // 作成も一覧と同じ requireScopeAccess を入口に持つ。一覧が SUPPORTER を通していた
            // 以上、同じ入口の作成も通っていないことを実測で押さえる（AC の抜けを塞ぐ）。
            setAuthentication(supporterId);
            mockMvc.perform(post("/api/v1/teams/{teamPublicId}/schedule-keeps", teamSlug)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("title", "SUPPORTERは作れないはず"))))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("SCHEDULE_KEEP_001"));
        }

        @Test
        @DisplayName("AC-16b: GUEST（当該チームに一切所属しない認証済み利用者）の単体GETは404")
        void AC16b_GUESTの単体GETは404() throws Exception {
            ScheduleKeepEntity keep = saveKeep(teamId, memberId, "GUESTには見えないはず", ScheduleKeepStatus.KEPT, 0);
            em.flush();
            em.clear();

            setAuthentication(outsiderId);
            mockMvc.perform(get("/api/v1/teams/{teamPublicId}/schedule-keeps/{keepId}", teamSlug, keep.getId()))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("SCHEDULE_KEEP_001"));
        }

        @Test
        @DisplayName("AC-16b: GUEST（当該チームに一切所属しない認証済み利用者）の一覧GETは404")
        void AC16b_GUESTの一覧GETは404() throws Exception {
            saveKeep(teamId, memberId, "GUESTには一覧も見えないはず", ScheduleKeepStatus.KEPT, 0);
            em.flush();
            em.clear();

            setAuthentication(outsiderId);
            mockMvc.perform(get("/api/v1/teams/{teamPublicId}/schedule-keeps", teamSlug))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("SCHEDULE_KEEP_001"));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // フィクスチャ
    // ═════════════════════════════════════════════════════════════════════

    private ScheduleKeepEntity saveKeep(Long teamId, Long createdBy, String title,
                                         ScheduleKeepStatus status, int sortOrder) {
        return scheduleKeepRepository.save(ScheduleKeepEntity.builder()
                .teamId(teamId)
                .title(title)
                .status(status)
                .sortOrder(sortOrder)
                .createdBy(createdBy)
                .build());
    }

    private void setAuthentication(Long userId) {
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
                                + "VALUES (:email, 'F0317', 'テスト', 'F0317 テスト', 'ACTIVE', "
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

    private Long insertTeam(String name, String slug) {
        em.createNativeQuery(
                        "INSERT INTO teams (name, visibility, supporter_enabled, version, member_count, slug, "
                                + "created_at, updated_at) "
                                + "VALUES (:name, 'PUBLIC', 1, 0, 0, :slug, NOW(), NOW())")
                .setParameter("name", name)
                .setParameter("slug", slug)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM teams WHERE slug = :slug")
                .setParameter("slug", slug)
                .getSingleResult()).longValue();
    }
}
