package com.mannschaft.app.schedule;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.notification.service.NotificationService;
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
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F03.16 予定コメントスレッド — AC-31（通知失敗の分離）契約テスト（試練）。
 *
 * <p>設計書: {@code docs/features/F03.16_schedule_comment_thread.md} §6.6 / §9.4 AC-31。</p>
 *
 * <h2>本テストが守らせること</h2>
 * <p><b>通知の失敗でコメント投稿が巻き戻ってはならない。</b> コメントの INSERT（schedule ドメイン）と
 * 通知の発火（notification ドメインへの越境）は分離し、通知は {@code afterCommit} に載せて
 * best-effort とする（原則5）。分離できていない実装では、通知サービスが例外を投げた瞬間に
 * コメント投稿ごとロールバックされ、ユーザーは「投稿したのに消えた」を踏む。</p>
 *
 * <h2>{@code ScheduleCommentNotificationContractIT} と分けた理由</h2>
 * <p>本クラスは {@link NotificationService} をモックへ置き換えるため、
 * 「通知が実際に作られること」を検証する AC-09 / AC-10 と同居できない
 * （同居させると通知が常に作られず、届くことの検証が構造的に成立しなくなる）。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("F03.16 予定コメント 通知失敗の分離 契約テスト（試練・AC-31）")
class ScheduleCommentNotificationIsolationContractIT extends AbstractMySqlIntegrationTest {

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

    /** 通知発火を例外へ差し替えるため実 Bean をモックへ置き換える。 */
    @MockitoBean
    private NotificationService notificationService;

    @MockitoBean
    private GoogleApiClient googleApiClient;

    @MockitoBean
    private GoogleCalendarWebhookService googleCalendarWebhookService;

    @PersistenceContext
    private EntityManager em;

    private Long authorId;
    private Long recipientId;
    private Long scheduleId;

    @BeforeEach
    void setUp() {
        String nonce = String.valueOf(System.nanoTime());
        transactionTemplate.executeWithoutResult(tx -> {
            Long teamId = ScheduleCommentTestFixtures.insertTeam(em, "F0316 分離", "sci-team-" + nonce);
            authorId = ScheduleCommentTestFixtures.insertUser(em, "sci-a-" + nonce + "@example.com", "投稿 A");
            recipientId = ScheduleCommentTestFixtures.insertUser(em, "sci-b-" + nonce + "@example.com", "受信 B");
            MembershipTestHelper.insertMembership(em, authorId, ScopeType.TEAM, teamId, RoleKind.MEMBER);
            MembershipTestHelper.insertMembership(em, recipientId, ScopeType.TEAM, teamId, RoleKind.MEMBER);
            em.flush();

            scheduleId = scheduleRepository.save(ScheduleEntity.builder()
                    .teamId(teamId)
                    .title("F0316 通知分離検証予定")
                    .startAt(LocalDateTime.of(2026, 9, 21, 10, 0))
                    .endAt(LocalDateTime.of(2026, 9, 21, 12, 0))
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
        Mockito.reset(notificationService);
    }

    @Test
    @DisplayName("AC-31 通知サービスが例外を投げてもコメントは 201 で保存され、投稿は巻き戻らない（通知は 0 件）")
    void AC31_通知失敗でコメント投稿は巻き戻らない() throws Exception {
        Mockito.doThrow(new IllegalStateException("通知基盤の障害を注入"))
                .when(notificationService)
                .createNotification(anyLong(), anyString(), any(), anyString(), any(),
                        anyString(), any(), any(), any(), any(), any());
        Mockito.doThrow(new IllegalStateException("通知基盤の障害を注入"))
                .when(notificationService)
                .createNotification(anyLong(), anyString(), any(), anyString(), any(),
                        anyString(), any(), any(), any(), any(), any(), any());

        setAuthentication(authorId);
        String response = mockMvc.perform(post(COMMENTS, scheduleId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(postBody("通知が壊れていても残るべき本文", List.of(recipientId))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        UUID createdId = UUID.fromString(objectMapper.readTree(response).path("data").path("id").asText());
        boolean persisted = Boolean.TRUE.equals(transactionTemplate.execute(
                tx -> scheduleCommentRepository.findById(createdId).isPresent()));
        assertThat(persisted)
                .as("通知の失敗でコメント投稿が巻き戻ってはならない（原則5・§6.6 の分離）")
                .isTrue();

        long notificationCount = transactionTemplate.execute(
                tx -> ScheduleCommentTestFixtures.countNotifications(em, recipientId));
        assertThat(notificationCount)
                .as("通知そのものは作られない（best-effort。握りつぶさずログに残す側の責務）")
                .isZero();
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
