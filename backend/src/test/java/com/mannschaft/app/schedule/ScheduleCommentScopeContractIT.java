package com.mannschaft.app.schedule;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.schedule.entity.ScheduleCommentEntity;
import com.mannschaft.app.schedule.entity.ScheduleEntity;
import com.mannschaft.app.schedule.repository.ScheduleCommentRepository;
import com.mannschaft.app.schedule.repository.ScheduleRepository;
import com.mannschaft.app.schedule.service.GoogleApiClient;
import com.mannschaft.app.schedule.service.GoogleCalendarWebhookService;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.MembershipTestHelper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F03.16 予定コメントスレッド — 認可・スコープ契約テスト（試練）。
 *
 * <p>設計書: {@code docs/features/F03.16_schedule_comment_thread.md}
 * §2.1 / §2.1.1 / §2.1.2 / §4.5 / §9.2。対応 AC: AC-12・AC-12b・AC-13・AC-14・AC-15b・
 * AC-16c・AC-16d・AC-17・AC-20・AC-33・AC-11b。</p>
 *
 * <h2>本テストが守らせる不変条件</h2>
 * <p><b>「親スケジュールを閲覧できる者だけがコメントを読み書きできる」</b>。判定は
 * {@code contentVisibilityChecker.canView(ReferenceType.SCHEDULE, scheduleId, userId)} 単体に
 * 一本化する（§4.5.0・マスター御裁可 2026-08-11）。コメント側で独自の可視性述語
 * （{@code team_id} の突き合わせ等）を書くと F00 の可視性ラダーと必ずずれ、漏洩源になる。
 * AC-12b はまさに「独自述語で所属だけを見る実装」に陥っていないかを撃ち抜く回帰である。</p>
 *
 * <h2>404 の期待値に添えて必ずエラーコードを検証する理由</h2>
 * <p>エンドポイント未実装の状態では Spring が素の 404 を返すため、ステータスだけを見ると
 * 「拒否されるべきものが拒否された」ように見えて<b>偽の緑</b>になる。したがって秘匿系 404 は
 * {@code $.error.code} まで検証し、実装が存在しなければ必ず赤になるようにする。</p>
 *
 * <h2>未認証の 401 について</h2>
 * <p>{@code addFilters = false} のため Spring Security のフィルタチェーンは通らないが、
 * {@code SecurityContextHolder.clearContext()} 後のリクエストは
 * {@code SecurityUtils.getCurrentUserId()} が {@code COMMON_000} を投げ、
 * {@code GlobalExceptionHandler} が 401 に写像する（既存 {@code *ScopeContractIT} と同じ作法）。</p>
 *
 * <h2>フィクスチャの所属は memberships が正</h2>
 * <p>MEMBER / SUPPORTER は {@code memberships} のみに張る（{@code V60.010} で移行済であり、
 * {@code user_roles} に MEMBER/SUPPORTER 行がある状態は本番に存在しえない）。
 * ADMIN / DEPUTY_ADMIN / SYSTEM_ADMIN は権限ロールなので {@code user_roles} を使う。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("F03.16 予定コメント 認可・スコープ契約テスト（試練）")
class ScheduleCommentScopeContractIT extends AbstractMySqlIntegrationTest {

    private static final String COMMENTS = "/api/v1/schedules/{scheduleId}/comments";
    private static final String COMMENT_BY_ID = "/api/v1/schedules/{scheduleId}/comments/{commentId}";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ScheduleRepository scheduleRepository;

    @Autowired
    private ScheduleCommentRepository scheduleCommentRepository;

    /** 外部 API 呼び出しは本テストの対象外のため遮断する。 */
    @MockitoBean
    private GoogleApiClient googleApiClient;

    @MockitoBean
    private GoogleCalendarWebhookService googleCalendarWebhookService;

    @PersistenceContext
    private EntityManager em;

    private Long teamId;
    private Long otherTeamId;
    private Long orgId;

    /** 予定を持つチームの MEMBER（正当な利用者）。 */
    private Long memberId;
    /** 同じチームのもう一人の MEMBER（他人のコメントを持つ役）。 */
    private Long otherMemberId;
    /** 同じチームに所属する SUPPORTER。MEMBER_PLUS 予定のコメントを読めてはならない。 */
    private Long supporterId;
    /** チームの ADMIN（モデレーション権限を持つ）。 */
    private Long teamAdminId;
    /** まったく別チームのユーザー（越境の検証用）。 */
    private Long outsiderId;
    /** どのスコープにも所属しないユーザー（role == null の fail-closed 検証用）。 */
    private Long unaffiliatedId;
    /** 組織の ADMIN（組織予定のモデレーション）。 */
    private Long orgAdminId;
    /** 組織の一般 MEMBER。 */
    private Long orgMemberId;

    @BeforeEach
    void setUp() {
        String nonce = String.valueOf(System.nanoTime());
        teamId = ScheduleCommentTestFixtures.insertTeam(em, "F0316 チーム", "sc-team-" + nonce);
        otherTeamId = ScheduleCommentTestFixtures.insertTeam(em, "F0316 別チーム", "sc-other-" + nonce);
        orgId = ScheduleCommentTestFixtures.insertOrganization(em, "F0316 組織", "sc-org-" + nonce);
        ScheduleCommentTestFixtures.linkTeamToOrganization(em, teamId, orgId);

        memberId = ScheduleCommentTestFixtures.insertUser(em, "sc-member-" + nonce + "@example.com", "山田 太郎");
        otherMemberId = ScheduleCommentTestFixtures.insertUser(em, "sc-other-member-" + nonce + "@example.com", "鈴木 花子");
        supporterId = ScheduleCommentTestFixtures.insertUser(em, "sc-supporter-" + nonce + "@example.com", "応援 三郎");
        teamAdminId = ScheduleCommentTestFixtures.insertUser(em, "sc-admin-" + nonce + "@example.com", "管理 四郎");
        outsiderId = ScheduleCommentTestFixtures.insertUser(em, "sc-outsider-" + nonce + "@example.com", "外部 五郎");
        unaffiliatedId = ScheduleCommentTestFixtures.insertUser(em, "sc-noscope-" + nonce + "@example.com", "無所属 六郎");
        orgAdminId = ScheduleCommentTestFixtures.insertUser(em, "sc-org-admin-" + nonce + "@example.com", "組織管理 七郎");
        orgMemberId = ScheduleCommentTestFixtures.insertUser(em, "sc-org-member-" + nonce + "@example.com", "組織一般 八郎");

        // 所属は memberships を正とする（user_roles に MEMBER/SUPPORTER を張らない）。
        MembershipTestHelper.insertMembership(em, memberId, ScopeType.TEAM, teamId, RoleKind.MEMBER);
        MembershipTestHelper.insertMembership(em, otherMemberId, ScopeType.TEAM, teamId, RoleKind.MEMBER);
        MembershipTestHelper.insertMembership(em, supporterId, ScopeType.TEAM, teamId, RoleKind.SUPPORTER);
        MembershipTestHelper.insertMembership(em, outsiderId, ScopeType.TEAM, otherTeamId, RoleKind.MEMBER);
        MembershipTestHelper.insertMembership(em, orgMemberId, ScopeType.ORGANIZATION, orgId, RoleKind.MEMBER);

        // 権限ロールは user_roles 側。
        MembershipTestHelper.insertMembership(em, teamAdminId, ScopeType.TEAM, teamId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, teamAdminId, "ADMIN", teamId, null);
        MembershipTestHelper.insertMembership(em, orgAdminId, ScopeType.ORGANIZATION, orgId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, orgAdminId, "ADMIN", null, orgId);

        em.flush();
        em.clear();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-33: 全 8 エンドポイントの「未認証 401 / 非メンバー 404 / 正当ユーザー 2xx」
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("AC-33 全8エンドポイントの認可契約")
    class AllEndpointsAuthzContract {

        @Test
        @DisplayName("AC-33 未認証では全8エンドポイントが401")
        void 未認証は全エンドポイント401() throws Exception {
            Long scheduleId = saveTeamSchedule(MinViewRole.MEMBER_PLUS);
            UUID commentId = saveComment(scheduleId, memberId, "本文", null);

            SecurityContextHolder.clearContext();
            mockMvc.perform(get(COMMENTS, scheduleId)).andExpect(status().isUnauthorized());
            mockMvc.perform(get(COMMENTS + "/meta", scheduleId)).andExpect(status().isUnauthorized());
            mockMvc.perform(get(COMMENT_BY_ID + "/replies", scheduleId, commentId))
                    .andExpect(status().isUnauthorized());
            mockMvc.perform(get(COMMENTS + "/mention-candidates", scheduleId))
                    .andExpect(status().isUnauthorized());
            mockMvc.perform(post(COMMENTS, scheduleId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(postBody("未認証投稿", null, null)))
                    .andExpect(status().isUnauthorized());
            mockMvc.perform(patch(COMMENT_BY_ID, scheduleId, commentId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"body\":\"未認証編集\"}"))
                    .andExpect(status().isUnauthorized());
            mockMvc.perform(delete(COMMENT_BY_ID, scheduleId, commentId))
                    .andExpect(status().isUnauthorized());
            mockMvc.perform(patch(COMMENTS + "/settings", scheduleId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"commentsEnabled\":false}"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("AC-33 正当な MEMBER は全8エンドポイントで 2xx（異常系だけの緑を許さない正常系の固定）")
        void 正当ユーザーは全エンドポイント2xx() throws Exception {
            Long scheduleId = saveTeamSchedule(MinViewRole.MEMBER_PLUS);
            UUID commentId = saveComment(scheduleId, memberId, "自分のコメント", null);

            setAuthentication(memberId);
            mockMvc.perform(get(COMMENTS, scheduleId)).andExpect(status().isOk());
            mockMvc.perform(get(COMMENTS + "/meta", scheduleId)).andExpect(status().isOk());
            mockMvc.perform(get(COMMENT_BY_ID + "/replies", scheduleId, commentId)).andExpect(status().isOk());
            mockMvc.perform(get(COMMENTS + "/mention-candidates", scheduleId)).andExpect(status().isOk());
            mockMvc.perform(post(COMMENTS, scheduleId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(postBody("新規投稿", null, null)))
                    .andExpect(status().isCreated());
            mockMvc.perform(patch(COMMENT_BY_ID, scheduleId, commentId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"body\":\"編集後\"}"))
                    .andExpect(status().isOk());
            mockMvc.perform(delete(COMMENT_BY_ID, scheduleId, commentId))
                    .andExpect(status().isNoContent());

            // settings は作成者に限られるため、作成者である MEMBER で叩く。
            setAuthentication(memberId);
            Long ownSchedule = saveTeamScheduleCreatedBy(memberId, MinViewRole.MEMBER_PLUS);
            mockMvc.perform(patch(COMMENTS + "/settings", ownSchedule)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"commentsEnabled\":false}"))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("AC-33 別チームのユーザーは全8エンドポイントで 404 SCHEDULE_COMMENT_002（存在秘匿）")
        void 非メンバーは全エンドポイント404() throws Exception {
            Long scheduleId = saveTeamSchedule(MinViewRole.MEMBER_PLUS);
            UUID commentId = saveComment(scheduleId, memberId, "本文", null);

            setAuthentication(outsiderId);
            mockMvc.perform(get(COMMENTS, scheduleId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("SCHEDULE_COMMENT_002"));
            mockMvc.perform(get(COMMENTS + "/meta", scheduleId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("SCHEDULE_COMMENT_002"));
            mockMvc.perform(get(COMMENT_BY_ID + "/replies", scheduleId, commentId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("SCHEDULE_COMMENT_002"));
            mockMvc.perform(get(COMMENTS + "/mention-candidates", scheduleId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("SCHEDULE_COMMENT_002"));
            mockMvc.perform(post(COMMENTS, scheduleId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(postBody("越境投稿", null, null)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("SCHEDULE_COMMENT_002"));
            mockMvc.perform(patch(COMMENT_BY_ID, scheduleId, commentId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"body\":\"越境編集\"}"))
                    .andExpect(status().isNotFound());
            mockMvc.perform(delete(COMMENT_BY_ID, scheduleId, commentId))
                    .andExpect(status().isNotFound());
            mockMvc.perform(patch(COMMENTS + "/settings", scheduleId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"commentsEnabled\":false}"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("SCHEDULE_COMMENT_002"));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-12 / AC-12b: 越境・min_view_role による遮断
    // ═════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("AC-12 別チームのユーザーが他チーム予定のコメント一覧を叩くと404で、本文も件数も一切返らない")
    void AC12_越境の一覧は404で情報を漏らさない() throws Exception {
        Long scheduleId = saveTeamSchedule(MinViewRole.MEMBER_PLUS);
        saveComment(scheduleId, memberId, "秘密の集合場所は体育館裏です", null);

        setAuthentication(outsiderId);
        String response = mockMvc.perform(get(COMMENTS, scheduleId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("SCHEDULE_COMMENT_002"))
                .andReturn().getResponse().getContentAsString();

        assertThat(response)
                .as("404 の応答本文にコメント本文・件数・予定タイトルが混入してはならない（存在秘匿）")
                .doesNotContain("体育館裏")
                .doesNotContain("total");
    }

    @Test
    @DisplayName("AC-12b【重大】min_view_role=MEMBER_PLUS の予定は同チーム SUPPORTER に404、SUPPORTER_PLUS なら同じ SUPPORTER が200")
    void AC12b_minViewRoleがSUPPORTERを遮断する() throws Exception {
        // canView を経由せず「同じチームに所属しているか」だけで判定する実装だと、
        // SUPPORTER にコメントが見えてしまう。その退行を撃ち抜く回帰である。
        Long memberPlusSchedule = saveTeamSchedule(MinViewRole.MEMBER_PLUS);
        saveComment(memberPlusSchedule, memberId, "MEMBER 限定の会話", null);

        setAuthentication(supporterId);
        String denied = mockMvc.perform(get(COMMENTS, memberPlusSchedule))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("SCHEDULE_COMMENT_002"))
                .andReturn().getResponse().getContentAsString();
        assertThat(denied)
                .as("MEMBER 限定予定のコメント本文が SUPPORTER へ1文字も渡ってはならない")
                .doesNotContain("MEMBER 限定の会話");

        // 塞ぎすぎていないこと（SUPPORTER_PLUS なら同じ SUPPORTER が読める）。
        Long supporterPlusSchedule = saveTeamSchedule(MinViewRole.SUPPORTER_PLUS);
        saveComment(supporterPlusSchedule, memberId, "応援者も読める会話", null);

        mockMvc.perform(get(COMMENTS, supporterPlusSchedule))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].body").value("応援者も読める会話"));
    }

    @Test
    @DisplayName("AC-12b SUPPORTER は MEMBER_PLUS 予定へ投稿もできない（読めない予定に書ける経路を作らない）")
    void AC12b_読めない予定への投稿も遮断される() throws Exception {
        Long scheduleId = saveTeamSchedule(MinViewRole.MEMBER_PLUS);

        setAuthentication(supporterId);
        mockMvc.perform(post(COMMENTS, scheduleId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(postBody("見えないはずの予定への投稿", null, null)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("SCHEDULE_COMMENT_002"));

        assertThat(ScheduleCommentTestFixtures.countComments(em, scheduleId))
                .as("拒否された投稿が DB に残ってはならない")
                .isZero();
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-13 / AC-14: IDOR・他人のコメントへの操作
    // ═════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("AC-13 予定Xのコメント ID を予定Yのパスに混ぜて PATCH すると 404 SCHEDULE_COMMENT_003 で本文も更新されない")
    void AC13_コメントIDの越境は404() throws Exception {
        Long scheduleX = saveTeamSchedule(MinViewRole.MEMBER_PLUS);
        Long scheduleY = saveTeamSchedule(MinViewRole.MEMBER_PLUS);
        UUID commentOnX = saveComment(scheduleX, memberId, "元の本文", null);

        setAuthentication(memberId);
        mockMvc.perform(patch(COMMENT_BY_ID, scheduleY, commentOnX)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"改竄された本文\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("SCHEDULE_COMMENT_003"));

        em.flush();
        em.clear();
        assertThat(scheduleCommentRepository.findById(commentOnX).orElseThrow().getBody())
                .as("越境 PATCH が拒否されても本文が書き換わっていては意味がない")
                .isEqualTo("元の本文");
    }

    @Test
    @DisplayName("AC-14 ADMIN でも他人のコメント本文の PATCH は 403 SCHEDULE_COMMENT_009、ただし DELETE は 204 で成功する")
    void AC14_ADMINは編集不可だが削除は可能() throws Exception {
        Long scheduleId = saveTeamSchedule(MinViewRole.MEMBER_PLUS);
        UUID commentId = saveComment(scheduleId, memberId, "一般メンバーの発言", null);

        setAuthentication(teamAdminId);
        mockMvc.perform(patch(COMMENT_BY_ID, scheduleId, commentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"ADMIN による改竄\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("SCHEDULE_COMMENT_009"));

        mockMvc.perform(delete(COMMENT_BY_ID, scheduleId, commentId))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("AC-14 一般 MEMBER は他人のコメントを編集も削除もできない（IDOR）")
    void AC14_一般メンバーは他人のコメントを操作できない() throws Exception {
        Long scheduleId = saveTeamSchedule(MinViewRole.MEMBER_PLUS);
        UUID commentId = saveComment(scheduleId, memberId, "他人の発言", null);

        setAuthentication(otherMemberId);
        mockMvc.perform(patch(COMMENT_BY_ID, scheduleId, commentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"乗っ取り\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("SCHEDULE_COMMENT_009"));

        mockMvc.perform(delete(COMMENT_BY_ID, scheduleId, commentId))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("SCHEDULE_COMMENT_010"));
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-15b: 401 と 403 の使い分け
    // ═════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("AC-15b【再訂正・殿の裁定 2026-08-11】GUEST ロール保持者はその予定を閲覧できるが、POST は 403 SCHEDULE_COMMENT_004（401 ではない）")
    void AC15b_認証済みで投稿要件を満たさなければ403() throws Exception {
        // 「誰か分からない」が 401、「誰か分かるが許可されない」が 403（§2.1）。
        //
        // 【再訂正】直前版は「認証済み・完全無所属（role == null）のユーザーが min_view_role=ANYONE の
        // 予定を閲覧できる」前提だったが、ScheduleVisibility に公開値が無く min_view_role は絞る方向
        // にしか働かないため、完全無所属では閲覧そのものが 404 に落ちる（§4.1 再訂正）。
        // 「閲覧できるが投稿要件を満たさない」を実在させるには、当該スコープに GUEST ロール
        // （user_roles）で所属していることが必要（AccessControlService#resolveEffectiveRoleName の
        // 「user_roles GUEST のみ → "GUEST"」経路。GUEST は SUPPORTER 未満のため postableRole を満たさない）。
        Long scheduleId = saveTeamSchedule(MinViewRole.ANYONE);
        MembershipTestHelper.insertUserRole(em, unaffiliatedId, "GUEST", teamId, null);
        em.flush();
        em.clear();

        setAuthentication(unaffiliatedId);
        mockMvc.perform(get(COMMENTS, scheduleId)).andExpect(status().isOk());

        mockMvc.perform(post(COMMENTS, scheduleId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(postBody("GUESTからの投稿", null, null)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("SCHEDULE_COMMENT_004"));
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-16c / AC-16d: スレッド開閉権限・組織予定のモデレーション
    // ═════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("AC-16c MANAGE_SCHEDULES を既定で持つ一般 MEMBER の settings は 403、同じ予定の作成者なら 200")
    void AC16c_スレッド開閉は作成者かADMINに限られる() throws Exception {
        // MANAGE_SCHEDULES は MEMBER に既定付与されているため、これを条件にすると
        // 一般 MEMBER が誰の予定のスレッドでも閉じられてしまう（§2.1.1）。
        Long othersSchedule = saveTeamScheduleCreatedBy(otherMemberId, MinViewRole.MEMBER_PLUS);

        setAuthentication(memberId);
        mockMvc.perform(patch(COMMENTS + "/settings", othersSchedule)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commentsEnabled\":false}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("SCHEDULE_COMMENT_011"));

        Long ownSchedule = saveTeamScheduleCreatedBy(memberId, MinViewRole.MEMBER_PLUS);
        mockMvc.perform(patch(COMMENTS + "/settings", ownSchedule)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commentsEnabled\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.commentsEnabled").value(false));
    }

    @Test
    @DisplayName("AC-16c チーム ADMIN は他人が作成した予定でも settings を変更できる")
    void AC16c_ADMINは他人の予定でも開閉できる() throws Exception {
        Long othersSchedule = saveTeamScheduleCreatedBy(otherMemberId, MinViewRole.MEMBER_PLUS);

        setAuthentication(teamAdminId);
        mockMvc.perform(patch(COMMENTS + "/settings", othersSchedule)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commentsEnabled\":false}"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("AC-16d 組織予定で組織 ADMIN は他者コメントを DELETE できる（204）が、組織の一般 MEMBER は 403 SCHEDULE_COMMENT_010")
    void AC16d_組織予定のモデレーションは組織ADMINで成立する() throws Exception {
        // DELETE_OTHERS_CONTENT は scope=TEAM でしか seed されていないため、
        // 組織予定で権限テーブルを引くと必ず false になり «誰もモデレートできない» 詰みになる。
        // 組織 ADMIN であることを条件にフォールバックする規則（§2.1.2）を固定する。
        Long orgSchedule = saveOrgSchedule(MinViewRole.MEMBER_PLUS);
        UUID commentId = saveComment(orgSchedule, orgMemberId, "組織予定への発言", null);

        setAuthentication(orgAdminId);
        mockMvc.perform(delete(COMMENT_BY_ID, orgSchedule, commentId))
                .andExpect(status().isNoContent());

        UUID second = saveComment(orgSchedule, orgAdminId, "ADMIN の発言", null);
        setAuthentication(orgMemberId);
        mockMvc.perform(delete(COMMENT_BY_ID, orgSchedule, second))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("SCHEDULE_COMMENT_010"));
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-17 / AC-20: 個人予定・論理削除済みの親
    // ═════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("AC-17 個人予定は所有者本人が叩いても全コメント API が 404 SCHEDULE_COMMENT_002（対象外機能）")
    void AC17_個人予定は本人でも404() throws Exception {
        Long personalSchedule = savePersonalSchedule(memberId);

        setAuthentication(memberId);
        mockMvc.perform(get(COMMENTS, personalSchedule))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("SCHEDULE_COMMENT_002"));
        mockMvc.perform(get(COMMENTS + "/meta", personalSchedule))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("SCHEDULE_COMMENT_002"));
        mockMvc.perform(post(COMMENTS, personalSchedule)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(postBody("個人予定への投稿", null, null)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("SCHEDULE_COMMENT_002"));
    }

    @Test
    @DisplayName("AC-20 親スケジュールが論理削除されるとコメント API は 404 SCHEDULE_COMMENT_002")
    void AC20_親が論理削除済みなら404() throws Exception {
        Long scheduleId = saveTeamSchedule(MinViewRole.MEMBER_PLUS);
        saveComment(scheduleId, memberId, "削除される予定のコメント", null);

        em.createNativeQuery("UPDATE schedules SET deleted_at = NOW() WHERE id = :id")
                .setParameter("id", scheduleId)
                .executeUpdate();
        em.flush();
        em.clear();

        setAuthentication(memberId);
        mockMvc.perform(get(COMMENTS, scheduleId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("SCHEDULE_COMMENT_002"));
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-11b: メンション候補の可視性
    // ═════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("AC-11b min_view_role=MEMBER_PLUS の予定のメンション候補に SUPPORTER が1人も含まれない（q 指定でも出ない）")
    void AC11b_メンション候補は可視性でフィルタされる() throws Exception {
        // 候補に並べば «選んでも通知が届かない» 体験になり、かつ SUPPORTER の名簿が漏れる。
        Long scheduleId = saveTeamSchedule(MinViewRole.MEMBER_PLUS);

        setAuthentication(memberId);
        mockMvc.perform(get(COMMENTS + "/mention-candidates", scheduleId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.userId == " + supporterId + ")]").isEmpty())
                .andExpect(jsonPath("$.data[?(@.userId == " + otherMemberId + ")]").isNotEmpty());

        mockMvc.perform(get(COMMENTS + "/mention-candidates", scheduleId).param("q", "応援"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.userId == " + supporterId + ")]").isEmpty());
    }

    @Test
    @DisplayName("AC-11b メンション候補は件数メタを返さない（母集団を数から推測させない）")
    void AC11b_候補応答にページメタを含めない() throws Exception {
        Long scheduleId = saveTeamSchedule(MinViewRole.MEMBER_PLUS);

        setAuthentication(memberId);
        mockMvc.perform(get(COMMENTS + "/mention-candidates", scheduleId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta").doesNotExist());
    }

    // ═════════════════════════════════════════════════════════════════════
    // ヘルパー
    // ═════════════════════════════════════════════════════════════════════

    private String postBody(String body, UUID parentId, List<Long> mentionedUserIds) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("body", body);
        payload.put("parentId", parentId == null ? null : parentId.toString());
        payload.put("mentionedUserIds", mentionedUserIds);
        return objectMapper.writeValueAsString(payload);
    }

    private Long saveTeamSchedule(MinViewRole minViewRole) {
        return saveTeamScheduleCreatedBy(teamAdminId, minViewRole);
    }

    private Long saveTeamScheduleCreatedBy(Long createdBy, MinViewRole minViewRole) {
        Long id = scheduleRepository.save(ScheduleEntity.builder()
                .teamId(teamId)
                .title("F0316 チーム予定 " + minViewRole)
                .startAt(LocalDateTime.of(2026, 9, 10, 10, 0))
                .endAt(LocalDateTime.of(2026, 9, 10, 12, 0))
                .eventType(EventType.PRACTICE)
                .visibility(ScheduleVisibility.MEMBERS_ONLY)
                .minViewRole(minViewRole)
                .status(ScheduleStatus.SCHEDULED)
                .attendanceRequired(true)
                .allowProxyAttendance(true)
                .isProxyAutoAccept(false)
                .createdBy(createdBy)
                .build()).getId();
        em.flush();
        em.clear();
        return id;
    }

    private Long saveOrgSchedule(MinViewRole minViewRole) {
        Long id = scheduleRepository.save(ScheduleEntity.builder()
                .organizationId(orgId)
                .title("F0316 組織予定 " + minViewRole)
                .startAt(LocalDateTime.of(2026, 9, 11, 10, 0))
                .endAt(LocalDateTime.of(2026, 9, 11, 12, 0))
                .eventType(EventType.OTHER)
                .visibility(ScheduleVisibility.MEMBERS_ONLY)
                .minViewRole(minViewRole)
                .includeSupporters(false)
                .status(ScheduleStatus.SCHEDULED)
                .attendanceRequired(false)
                .allowProxyAttendance(true)
                .isProxyAutoAccept(false)
                .createdBy(orgAdminId)
                .build()).getId();
        em.flush();
        em.clear();
        return id;
    }

    private Long savePersonalSchedule(Long ownerId) {
        Long id = scheduleRepository.save(ScheduleEntity.builder()
                .userId(ownerId)
                .title("F0316 個人予定")
                .startAt(LocalDateTime.of(2026, 9, 12, 10, 0))
                .endAt(LocalDateTime.of(2026, 9, 12, 12, 0))
                .eventType(EventType.OTHER)
                .visibility(ScheduleVisibility.MEMBERS_ONLY)
                .minViewRole(MinViewRole.ADMIN_ONLY)
                .status(ScheduleStatus.SCHEDULED)
                .createdBy(ownerId)
                .build()).getId();
        em.flush();
        em.clear();
        return id;
    }

    private UUID saveComment(Long scheduleId, Long authorId, String body, UUID parentId) {
        ScheduleCommentEntity saved = scheduleCommentRepository.save(ScheduleCommentEntity.builder()
                .scheduleId(scheduleId)
                .userId(authorId)
                .body(body)
                .parentId(parentId)
                .rootId(parentId)
                .depth(parentId == null ? 0 : 1)
                .build());
        em.flush();
        em.clear();
        return saved.getId();
    }

    private void setAuthentication(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
    }
}
