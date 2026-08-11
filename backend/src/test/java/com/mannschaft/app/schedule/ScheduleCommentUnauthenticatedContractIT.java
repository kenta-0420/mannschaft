package com.mannschaft.app.schedule;

import com.mannschaft.app.schedule.entity.ScheduleCommentEntity;
import com.mannschaft.app.schedule.entity.ScheduleEntity;
import com.mannschaft.app.schedule.repository.ScheduleCommentRepository;
import com.mannschaft.app.schedule.repository.ScheduleRepository;
import com.mannschaft.app.schedule.service.GoogleApiClient;
import com.mannschaft.app.schedule.service.GoogleCalendarWebhookService;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F03.16 予定コメントスレッド — 未ログイン（GUEST）契約テスト（試練・AC-15）。
 *
 * <p>設計書: {@code docs/features/F03.16_schedule_comment_thread.md} §2.1 / §4.1 / §9.2 AC-15。</p>
 *
 * <h2>本書の GUEST の定義</h2>
 * <p><b>GUEST ＝ 未ログインの閲覧者</b>である。{@code min_view_role = ANYONE} の予定に限り
 * <b>{@code GET} 一覧のみ</b>が認証不要で、それ以外（{@code meta} / {@code replies} /
 * {@code mention-candidates} / POST / PATCH / DELETE / settings）は<b>すべて認証必須で 401</b>。
 * 401 と 403 を混同しない — 「誰か分からない」が 401、「誰か分かるが許可されない」が 403。</p>
 *
 * <h2>{@code addFilters = false} を使わない理由</h2>
 * <p>他の契約 IT は {@code addFilters = false} で Spring Security のフィルタチェーンを外して
 * {@code SecurityContextHolder} を直接差し替えるため、<b>本来のフィルタ由来の認証要否を検証できない</b>。
 * 「一覧だけが認証不要」という本機能固有の穴が正しく開いているかは、実フィルタチェーンを
 * 通さないと確かめられない（金型: {@code ScheduleKeepUnauthenticatedContractIT}）。</p>
 */
@AutoConfigureMockMvc
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("F03.16 予定コメント 未ログイン契約テスト（試練・AC-15）")
class ScheduleCommentUnauthenticatedContractIT extends AbstractMySqlIntegrationTest {

    private static final String COMMENTS = "/api/v1/schedules/{scheduleId}/comments";
    private static final String COMMENT_BY_ID = "/api/v1/schedules/{scheduleId}/comments/{commentId}";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ScheduleRepository scheduleRepository;

    @Autowired
    private ScheduleCommentRepository scheduleCommentRepository;

    @MockitoBean
    private GoogleApiClient googleApiClient;

    @MockitoBean
    private GoogleCalendarWebhookService googleCalendarWebhookService;

    @PersistenceContext
    private EntityManager em;

    /** min_view_role = ANYONE の予定（未ログインでも一覧だけは読める）。 */
    private Long anyoneScheduleId;
    /** min_view_role = MEMBER_PLUS の予定（未ログインでは一覧すら読めない）。 */
    private Long memberPlusScheduleId;
    private UUID commentOnAnyone;

    @BeforeEach
    void setUp() {
        String nonce = String.valueOf(System.nanoTime());
        Long teamId = ScheduleCommentTestFixtures.insertTeam(em, "F0316 未ログイン", "scu-team-" + nonce);
        Long ownerId = ScheduleCommentTestFixtures.insertUser(em, "scu-owner-" + nonce + "@example.com", "作成 者");
        em.flush();

        anyoneScheduleId = saveSchedule(teamId, ownerId, MinViewRole.ANYONE);
        memberPlusScheduleId = saveSchedule(teamId, ownerId, MinViewRole.MEMBER_PLUS);
        commentOnAnyone = saveComment(anyoneScheduleId, ownerId, "誰でも読める公開コメント");
    }

    @Test
    @DisplayName("AC-15 min_view_role=ANYONE の予定は未ログインでも一覧 GET が 200 で読める")
    void AC15_未ログインでもANYONEの一覧は200() throws Exception {
        mockMvc.perform(get(COMMENTS, anyoneScheduleId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].body").value("誰でも読める公開コメント"))
                // 未ログインには編集・削除の余地がないことを応答でも示す。
                .andExpect(jsonPath("$.data[0].canEdit").value(false))
                .andExpect(jsonPath("$.data[0].canDelete").value(false));
    }

    @Test
    @DisplayName("AC-15 同じ未ログインコンテキストでも POST は 401（403 ではない）")
    void AC15_未ログインのPOSTは401() throws Exception {
        mockMvc.perform(post(COMMENTS, anyoneScheduleId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"未ログイン投稿\",\"parentId\":null,\"mentionedUserIds\":null}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("AC-15 meta / replies / mention-candidates は ANYONE の予定でも未ログインなら 401")
    void AC15_一覧以外は未ログインで401() throws Exception {
        mockMvc.perform(get(COMMENTS + "/meta", anyoneScheduleId))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get(COMMENT_BY_ID + "/replies", anyoneScheduleId, commentOnAnyone))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get(COMMENTS + "/mention-candidates", anyoneScheduleId))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("AC-15 min_view_role=MEMBER_PLUS の予定は未ログインでは一覧 GET も 401（穴を広げすぎない）")
    void AC15_ANYONE以外の一覧は未ログインで401() throws Exception {
        mockMvc.perform(get(COMMENTS, memberPlusScheduleId))
                .andExpect(status().isUnauthorized());
    }

    private Long saveSchedule(Long teamId, Long ownerId, MinViewRole minViewRole) {
        Long id = scheduleRepository.save(ScheduleEntity.builder()
                .teamId(teamId)
                .title("F0316 未ログイン検証予定 " + minViewRole)
                .startAt(LocalDateTime.of(2026, 9, 30, 10, 0))
                .endAt(LocalDateTime.of(2026, 9, 30, 12, 0))
                .eventType(EventType.PRACTICE)
                .visibility(ScheduleVisibility.MEMBERS_ONLY)
                .minViewRole(minViewRole)
                .status(ScheduleStatus.SCHEDULED)
                .attendanceRequired(true)
                .allowProxyAttendance(true)
                .isProxyAutoAccept(false)
                .createdBy(ownerId)
                .build()).getId();
        em.flush();
        em.clear();
        return id;
    }

    private UUID saveComment(Long scheduleId, Long authorId, String body) {
        ScheduleCommentEntity saved = scheduleCommentRepository.save(ScheduleCommentEntity.builder()
                .scheduleId(scheduleId)
                .userId(authorId)
                .body(body)
                .depth(0)
                .build());
        em.flush();
        em.clear();
        return saved.getId();
    }
}
