package com.mannschaft.app.schedule;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.schedule.entity.ScheduleEntity;
import com.mannschaft.app.schedule.repository.ScheduleCommentRepository;
import com.mannschaft.app.schedule.repository.ScheduleRepository;
import com.mannschaft.app.schedule.service.GoogleApiClient;
import com.mannschaft.app.schedule.service.GoogleCalendarWebhookService;
import com.mannschaft.app.schedule.service.ScheduleCommentNotificationRunner;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.willAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F03.16 予定コメントスレッド — 受信者 1 名分の通知失敗が他の受信者の通知を巻き添えにしないことの
 * 実 DB 検証（殿の追加課題・通知バッチのトランザクション分離）。
 *
 * <h2>背景（根治した欠陥）</h2>
 * <p>是正前の {@code ScheduleCommentNotifier#notify} は {@code @Transactional(REQUIRES_NEW)}
 * <b>一本の中で</b>受信者ごとに {@code createNotification} を呼び、各呼び出しを {@code try/catch} で
 * 握っていた。{@code createNotification} は既定の {@code REQUIRED} 伝播のため、1 受信者の失敗で
 * {@code notify} 全体のトランザクションがロールバックオンリーになり、catch して続行した他の
 * 受信者の通知もコミット時にまとめて消えていた（{@code UnexpectedRollbackException}）。
 * 本プロジェクトで #2655 / #2660 / #2664 の3ドメインで独立に発見された既知の形と同型。</p>
 *
 * <h2>{@code ScheduleCommentNotificationIsolationContractIT}（AC-31）と分けた理由</h2>
 * <p>AC-31 は {@link com.mannschaft.app.notification.service.NotificationService} を丸ごと
 * モックへ置き換えており、「通知が実際に届くこと」までは検証できない（全滅の検証専用）。
 * 本クラスは {@code NotificationService} を実 Bean のまま残し、{@link ScheduleCommentNotificationRunner}
 * （1 受信者 = 1 独立トランザクションの REQUIRES_NEW 実行 Bean）だけを spy して特定の受信者のみ
 * 失敗させることで、「残り 2 名の通知が実際に DB に残ること」を実測する
 * （CMP-035 {@code NotificationCreditMonthlyResetBatchIntegrationTest} と同型の手法）。</p>
 *
 * <h2>クラスに {@code @Transactional} を付けない理由</h2>
 * <p>通知は {@code afterCommit}（§6.6）で発火する。テストメソッドをトランザクションで包むと
 * コミットが起きず通知が1件も作られないまま「届かないことを確認できた」ことになってしまう
 * （偽の緑）。よって本クラスはトランザクションを張らず、フィクスチャ投入は
 * {@link TransactionTemplate} で明示的にコミットする。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("F03.16 予定コメント 通知バッチのトランザクション分離（1受信者失敗が他へ波及しない）")
class ScheduleCommentNotificationPartialFailureIT extends AbstractMySqlIntegrationTest {

    private static final String COMMENTS = "/api/v1/schedules/{scheduleId}/comments";

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

    /**
     * 1受信者分離の検証用 spy。{@link com.mannschaft.app.notification.service.NotificationService}
     * は実 Bean のまま（モックしない）で、{@link ScheduleCommentNotificationRunner#sendOne} だけを
     * 部分的に差し替え、特定の受信者だけを失敗させ、他の受信者は {@code callRealMethod()} で
     * 実処理（REQUIRES_NEW・実DB保存）まで通す。
     */
    @MockitoSpyBean
    private ScheduleCommentNotificationRunner notificationRunner;

    @PersistenceContext
    private EntityManager em;

    private Long authorId;
    private Long recipientOkId;
    private Long recipientBrokenId;
    private Long recipientOk2Id;
    private Long scheduleId;

    @BeforeEach
    void setUp() {
        String nonce = String.valueOf(System.nanoTime());
        transactionTemplate.executeWithoutResult(tx -> {
            Long teamId = ScheduleCommentTestFixtures.insertTeam(em, "F0316 分離バッチ", "scpf-team-" + nonce);
            authorId = ScheduleCommentTestFixtures.insertUser(em, "scpf-a-" + nonce + "@example.com", "投稿 A");
            recipientOkId = ScheduleCommentTestFixtures.insertUser(em, "scpf-b-" + nonce + "@example.com", "受信 B");
            recipientBrokenId = ScheduleCommentTestFixtures.insertUser(em, "scpf-c-" + nonce + "@example.com", "受信 C（失敗させる）");
            recipientOk2Id = ScheduleCommentTestFixtures.insertUser(em, "scpf-d-" + nonce + "@example.com", "受信 D");
            MembershipTestHelper.insertMembership(em, authorId, ScopeType.TEAM, teamId, RoleKind.MEMBER);
            MembershipTestHelper.insertMembership(em, recipientOkId, ScopeType.TEAM, teamId, RoleKind.MEMBER);
            MembershipTestHelper.insertMembership(em, recipientBrokenId, ScopeType.TEAM, teamId, RoleKind.MEMBER);
            MembershipTestHelper.insertMembership(em, recipientOk2Id, ScopeType.TEAM, teamId, RoleKind.MEMBER);
            em.flush();

            scheduleId = scheduleRepository.save(ScheduleEntity.builder()
                    .teamId(teamId)
                    .title("F0316 通知バッチ分離検証予定")
                    .startAt(LocalDateTime.of(2026, 9, 22, 10, 0))
                    .endAt(LocalDateTime.of(2026, 9, 22, 12, 0))
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

    @Test
    @DisplayName("受信者3人のうち1人分だけ失敗しても、残り2人の通知は実際にDBへ残る（1受信者=1独立トランザクション）")
    void 受信者1人の失敗が他の受信者の通知を巻き添えにしない() throws Exception {
        // recipientBrokenId 宛の送信だけ失敗させ、他は実処理（REQUIRES_NEW・実DB保存）を通す。
        willAnswer(invocation -> {
            Long targetUserId = invocation.getArgument(0);
            if (targetUserId.equals(recipientBrokenId)) {
                throw new RuntimeException("模擬通知送信失敗（通知バッチ分離検証用）");
            }
            return invocation.callRealMethod();
        }).given(notificationRunner).sendOne(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());

        setAuthentication(authorId);
        String response = mockMvc.perform(post(COMMENTS, scheduleId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(postBody("1人だけ失敗するメンション本文",
                                List.of(recipientOkId, recipientBrokenId, recipientOk2Id))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        assertThat(objectMapper.readTree(response).path("data").path("id").asText()).isNotBlank();

        // 本丸: 失敗した受信者以外の通知が実際にコミットされて残っていること。
        long okCount = transactionTemplate.execute(
                tx -> ScheduleCommentTestFixtures.countNotifications(em, recipientOkId));
        long ok2Count = transactionTemplate.execute(
                tx -> ScheduleCommentTestFixtures.countNotifications(em, recipientOk2Id));
        long brokenCount = transactionTemplate.execute(
                tx -> ScheduleCommentTestFixtures.countNotifications(em, recipientBrokenId));

        assertThat(okCount)
                .as("失敗させていない受信者Bの通知は、他受信者の失敗に巻き添えられずコミットされて残る")
                .isEqualTo(1);
        assertThat(ok2Count)
                .as("失敗させていない受信者Dの通知も同様にコミットされて残る")
                .isEqualTo(1);
        assertThat(brokenCount)
                .as("失敗させた受信者C自身の通知は作られない（best-effort。他へは巻き添えない）")
                .isZero();
    }

    @Test
    @DisplayName("全員成功時は3人全員の通知が独立にコミットされる")
    void 全員成功時は全受信者の通知が独立にコミットされる() throws Exception {
        setAuthentication(authorId);
        mockMvc.perform(post(COMMENTS, scheduleId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(postBody("全員成功する本文",
                                List.of(recipientOkId, recipientBrokenId, recipientOk2Id))))
                .andExpect(status().isCreated());

        assertThatCode(() -> {
            long a = transactionTemplate.execute(
                    tx -> ScheduleCommentTestFixtures.countNotifications(em, recipientOkId));
            long b = transactionTemplate.execute(
                    tx -> ScheduleCommentTestFixtures.countNotifications(em, recipientBrokenId));
            long c = transactionTemplate.execute(
                    tx -> ScheduleCommentTestFixtures.countNotifications(em, recipientOk2Id));
            assertThat(a).isEqualTo(1);
            assertThat(b).isEqualTo(1);
            assertThat(c).isEqualTo(1);
        }).doesNotThrowAnyException();
    }

    private String postBody(String body, List<Long> mentionedUserIds) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("body", body);
        payload.put("parentId", null);
        payload.put("mentionedUserIds", mentionedUserIds);
        return objectMapper.writeValueAsString(payload);
    }

    private void setAuthentication(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
    }
}
