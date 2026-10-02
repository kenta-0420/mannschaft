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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F03.16 予定コメントスレッド — メンション・返信通知の契約テスト（試練）。
 *
 * <p>設計書: {@code docs/features/F03.16_schedule_comment_thread.md} §6 / §9。
 * 対応 AC: AC-09・AC-10・AC-18・AC-18b・AC-23・AC-24・AC-25。AC-31（通知失敗の分離）は通知 Bean のモック化が
 * 他の AC と両立しないため {@code ScheduleCommentNotificationIsolationContractIT} に分離した。</p>
 *
 * <h2>最重要 — 通知は情報漏洩経路である</h2>
 * <p>通知の本文にはコメント本文の冒頭が載るため、<b>閲覧権限のないユーザーへ送ると本文が漏れる</b>。
 * AC-18b は、参照側（{@code ScheduleCommentScopeContractIT} の AC-12b）と通知側が
 * <b>同一の {@code canView} 呼び出し</b>を使っていることの実証であり、通知経路だけが
 * 独自判定に落ちていないかを撃ち抜く。</p>
 *
 * <h2>クラスに {@code @Transactional} を付けない理由</h2>
 * <p>通知は {@code afterCommit}（§6.6）で発火する。テストメソッドをトランザクションで包むと
 * コミットが起きず<b>通知が1件も作られないまま「届かないことを確認できた」ことになってしまう</b>
 * （AC-18・AC-18b・AC-24 が構造的に偽の緑になる）。よって本クラスはトランザクションを張らず、
 * フィクスチャ投入は {@link TransactionTemplate} で明示的にコミットする。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("F03.16 予定コメント 通知契約テスト（試練）")
class ScheduleCommentNotificationContractIT extends AbstractMySqlIntegrationTest {

    private static final String COMMENTS = "/api/v1/schedules/{scheduleId}/comments";
    private static final String MENTIONED = "SCHEDULE_COMMENT_MENTIONED";
    private static final String REPLIED = "SCHEDULE_COMMENT_REPLIED";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ScheduleRepository scheduleRepository;

    @Autowired
    private ScheduleCommentRepository scheduleCommentRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @MockitoBean
    private GoogleApiClient googleApiClient;

    @MockitoBean
    private GoogleCalendarWebhookService googleCalendarWebhookService;

    @PersistenceContext
    private EntityManager em;

    private Long teamId;
    /** 投稿者 A。 */
    private Long authorId;
    /** 同チーム MEMBER B（メンション・返信の相手）。 */
    private Long memberBId;
    /** 別チームのユーザー C（この予定を閲覧できない）。 */
    private Long outsiderCId;
    /** 同チームの SUPPORTER（MEMBER_PLUS 予定を閲覧できない）。 */
    private Long supporterId;
    private Long scheduleId;

    @BeforeEach
    void setUp() {
        String nonce = String.valueOf(System.nanoTime());
        transactionTemplate.executeWithoutResult(tx -> {
            teamId = ScheduleCommentTestFixtures.insertTeam(em, "F0316 通知", "scn-team-" + nonce);
            Long otherTeamId = ScheduleCommentTestFixtures.insertTeam(em, "F0316 通知別", "scn-other-" + nonce);

            authorId = ScheduleCommentTestFixtures.insertUser(em, "scn-a-" + nonce + "@example.com", "投稿 A");
            memberBId = ScheduleCommentTestFixtures.insertUser(em, "scn-b-" + nonce + "@example.com", "受信 B");
            outsiderCId = ScheduleCommentTestFixtures.insertUser(em, "scn-c-" + nonce + "@example.com", "圏外 C");
            supporterId = ScheduleCommentTestFixtures.insertUser(em, "scn-s-" + nonce + "@example.com", "応援 S");

            MembershipTestHelper.insertMembership(em, authorId, ScopeType.TEAM, teamId, RoleKind.MEMBER);
            MembershipTestHelper.insertMembership(em, memberBId, ScopeType.TEAM, teamId, RoleKind.MEMBER);
            MembershipTestHelper.insertMembership(em, supporterId, ScopeType.TEAM, teamId, RoleKind.SUPPORTER);
            MembershipTestHelper.insertMembership(em, outsiderCId, ScopeType.TEAM, otherTeamId, RoleKind.MEMBER);
            em.flush();

            scheduleId = scheduleRepository.save(ScheduleEntity.builder()
                    .teamId(teamId)
                    .title("F0316 通知検証予定")
                    .startAt(LocalDateTime.of(2026, 9, 20, 10, 0))
                    .endAt(LocalDateTime.of(2026, 9, 20, 12, 0))
                    .eventType(EventType.PRACTICE)
                    .visibility(ScheduleVisibility.MEMBERS_ONLY)
                    .minViewRole(MinViewRole.MEMBER_PLUS)
                    .status(ScheduleStatus.SCHEDULED)
                    .attendanceRequired(true)
                    .allowProxyAttendance(true)
                    .isProxyAutoAccept(false)
                    .createdBy(authorId)
                    .build()).getId();
        });
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-09 / AC-10: メンション通知・返信通知が届く
    // ═════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("AC-09 B をメンションして投稿すると B に SCHEDULE_COMMENT_MENTIONED が1件届き、actionUrl が /calendar?scheduleId=&commentId= を指す")
    void AC09_メンション通知が届く() throws Exception {
        setAuthentication(authorId);
        String response = mockMvc.perform(post(COMMENTS, scheduleId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(postBody("@受信 B 集合は何時ですか？", null, List.of(memberBId))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        String commentId = objectMapper.readTree(response).path("data").path("id").asText();

        assertThat(countNotifications(memberBId, MENTIONED))
                .as("メンションされた本人へ通知が1件作られること")
                .isEqualTo(1L);

        Map<String, Object> notification = latestNotification(memberBId);
        assertThat((String) notification.get("action_url"))
                .as("/schedules/{id} は存在しないルート。着地点は /calendar でなければ死んだリンクになる")
                .isEqualTo("/calendar?scheduleId=" + scheduleId + "&commentId=" + commentId);
        assertThat((String) notification.get("source_type")).isEqualTo("SCHEDULE_COMMENT");
        assertThat(notification.get("source_id"))
                .as("コメント ID は UUIDv7 であり Long の source_id には載らない（識別子は actionUrl が運ぶ）")
                .isNull();
        assertThat(((Number) notification.get("actor_id")).longValue()).isEqualTo(authorId);
    }

    @Test
    @DisplayName("AC-10 A のコメントに B が返信すると A に SCHEDULE_COMMENT_REPLIED が1件届く")
    void AC10_返信通知が届く() throws Exception {
        UUID parentId = saveComment(authorId, "持ち物は何ですか？", null);

        setAuthentication(memberBId);
        mockMvc.perform(post(COMMENTS, scheduleId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(postBody("タオルと水筒です", parentId, null)))
                .andExpect(status().isCreated());

        assertThat(countNotifications(authorId, REPLIED)).isEqualTo(1L);
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-18 / AC-18b: 可視性フィルタ（漏洩の遮断）
    // ═════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("AC-18 閲覧できない C をメンションしても投稿は 201 で成功し、C には通知が1件も届かない（同時指定の B には届く）")
    void AC18_閲覧できない相手はエラーにせず黙って除外する() throws Exception {
        // エラーを返すと «その予定の可視性を探る手段» になるため、拒否ではなく黙殺が正しい。
        setAuthentication(authorId);
        mockMvc.perform(post(COMMENTS, scheduleId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(postBody("秘密の集合場所は体育館裏です", null, List.of(memberBId, outsiderCId))))
                .andExpect(status().isCreated());

        assertThat(countNotifications(memberBId, MENTIONED))
                .as("閲覧できる B には届くこと（塞ぎすぎていない）")
                .isEqualTo(1L);
        assertThat(countNotifications(outsiderCId))
                .as("閲覧できない C へ通知が作られると、通知本文経由でコメントが漏れる")
                .isZero();
    }

    @Test
    @DisplayName("AC-18b【重大】MEMBER_PLUS 予定で同チーム SUPPORTER をメンションしても、その SUPPORTER に通知が1件も生成されない")
    void AC18b_minViewRoleは通知経路でも効く() throws Exception {
        // 参照側（AC-12b）と通知側が同一の canView 呼び出しを使っていることの実証。
        // 「同じチームに所属しているか」だけで通知先を決める実装だと SUPPORTER に本文が漏れる。
        setAuthentication(authorId);
        mockMvc.perform(post(COMMENTS, scheduleId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(postBody("MEMBER 限定の連絡事項です", null, List.of(supporterId))))
                .andExpect(status().isCreated());

        assertThat(countNotifications(supporterId))
                .as("min_view_role=MEMBER_PLUS の予定の本文抜粋が SUPPORTER へ渡ってはならない")
                .isZero();
    }

    @Test
    @DisplayName("AC-18b 返信通知も発火時点で再評価される（親コメント投稿者がチームを離脱していれば通知は作られない）")
    void AC18b_返信通知も発火時点で再評価される() throws Exception {
        // 投稿時点の権限をキャッシュすると、降格・離脱後も通知が飛び続けて本文が漏れる。
        UUID parentId = saveComment(authorId, "私の質問", null);

        transactionTemplate.executeWithoutResult(tx ->
                em.createNativeQuery("UPDATE memberships SET left_at = NOW() "
                                + "WHERE user_id = :uid AND scope_id = :sid AND scope_type = 'TEAM'")
                        .setParameter("uid", authorId)
                        .setParameter("sid", teamId)
                        .executeUpdate());

        setAuthentication(memberBId);
        mockMvc.perform(post(COMMENTS, scheduleId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(postBody("回答です", parentId, null)))
                .andExpect(status().isCreated());

        assertThat(countNotifications(authorId, REPLIED))
                .as("離脱済みユーザーへ返信通知を送ると本文抜粋が漏れる")
                .isZero();
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-23 / AC-24 / AC-25: メンションの境界と重複排除
    // ═════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("AC-23 mentionedUserIds が 20 件ちょうどは 201、21 件は 400 SCHEDULE_COMMENT_008")
    void AC23_メンション件数の境界() throws Exception {
        List<Long> twenty = createExtraMembers(20);
        List<Long> twentyOne = new ArrayList<>(twenty);
        twentyOne.addAll(createExtraMembers(1));

        setAuthentication(authorId);
        mockMvc.perform(post(COMMENTS, scheduleId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(postBody("20件メンション", null, twenty)))
                .andExpect(status().isCreated());

        mockMvc.perform(post(COMMENTS, scheduleId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(postBody("21件メンション", null, twentyOne)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("SCHEDULE_COMMENT_008"));
    }

    @Test
    @DisplayName("AC-24 自分自身をメンションしても 201 で成功するが、自分宛の通知は生成されない")
    void AC24_自己メンションは通知しない() throws Exception {
        setAuthentication(authorId);
        mockMvc.perform(post(COMMENTS, scheduleId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(postBody("自分にメンション", null, List.of(authorId))))
                .andExpect(status().isCreated());

        assertThat(countNotifications(authorId)).isZero();
    }

    @Test
    @DisplayName("AC-25 A のコメントに B が「A をメンションしつつ返信」しても、A への通知は MENTIONED 1通のみ")
    void AC25_メンションと返信の重複は1通に集約される() throws Exception {
        UUID parentId = saveComment(authorId, "A の質問", null);

        setAuthentication(memberBId);
        mockMvc.perform(post(COMMENTS, scheduleId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(postBody("@投稿 A 回答です", parentId, List.of(authorId))))
                .andExpect(status().isCreated());

        assertThat(countNotifications(authorId))
                .as("同一コメントで2通届くと通知疲れの原因になる")
                .isEqualTo(1L);
        assertThat(countNotifications(authorId, MENTIONED))
                .as("重複時はメンションを優先する")
                .isEqualTo(1L);
    }

    // ═════════════════════════════════════════════════════════════════════
    // ヘルパー
    // ═════════════════════════════════════════════════════════════════════

    private List<Long> createExtraMembers(int count) {
        List<Long> ids = new ArrayList<>();
        transactionTemplate.executeWithoutResult(tx -> {
            for (int i = 0; i < count; i++) {
                Long id = ScheduleCommentTestFixtures.insertUser(
                        em, "scn-extra-" + System.nanoTime() + "-" + i + "@example.com", "追加 " + i);
                MembershipTestHelper.insertMembership(em, id, ScopeType.TEAM, teamId, RoleKind.MEMBER);
                ids.add(id);
            }
            em.flush();
        });
        return ids;
    }

    private long countNotifications(Long userId) {
        return transactionTemplate.execute(tx ->
                ScheduleCommentTestFixtures.countNotifications(em, userId));
    }

    private long countNotifications(Long userId, String notificationType) {
        return transactionTemplate.execute(tx ->
                ScheduleCommentTestFixtures.countNotifications(em, userId, notificationType));
    }

    private Map<String, Object> latestNotification(Long userId) {
        return transactionTemplate.execute(tx -> {
            Object[] row = (Object[]) em.createNativeQuery(
                            "SELECT action_url, source_type, source_id, actor_id FROM notifications "
                                    + "WHERE user_id = :uid ORDER BY id DESC LIMIT 1")
                    .setParameter("uid", userId)
                    .getSingleResult();
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("action_url", row[0]);
            map.put("source_type", row[1]);
            map.put("source_id", row[2]);
            map.put("actor_id", row[3]);
            return map;
        });
    }

    private UUID saveComment(Long userId, String body, UUID parentId) {
        return transactionTemplate.execute(tx -> {
            ScheduleCommentEntity saved = scheduleCommentRepository.save(ScheduleCommentEntity.builder()
                    .scheduleId(scheduleId)
                    .userId(userId)
                    .body(body)
                    .parentId(parentId)
                    .rootId(parentId)
                    .depth(parentId == null ? 0 : 1)
                    .build());
            em.flush();
            return saved.getId();
        });
    }

    private String postBody(String body, UUID parentId, List<Long> mentionedUserIds) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("body", body);
        payload.put("parentId", parentId == null ? null : parentId.toString());
        payload.put("mentionedUserIds", mentionedUserIds);
        return objectMapper.writeValueAsString(payload);
    }

    private void setAuthentication(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
    }
}
