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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F03.16 予定コメントスレッド — 未ログイン契約テスト（試練・AC-15）。
 *
 * <p>設計書: {@code docs/features/F03.16_schedule_comment_thread.md} §2.1 / §4.1 / §9.2 AC-15。</p>
 *
 * <h2>【再訂正 2026-08-11・殿の裁定】全 8 エンドポイントが認証必須</h2>
 * <p>直前版は「{@code min_view_role = ANYONE} の予定に限り {@code GET} 一覧のみ認証不要」としていたが、
 * {@code ScheduleVisibility}（{@code backend/.../schedule/ScheduleVisibility.java}）の値は
 * {@code MEMBERS_ONLY} / {@code ORGANIZATION} / {@code CUSTOM_TEMPLATE} の3つのみで「公開」に相当する
 * 値が存在せず、{@code min_view_role} は CMP-017b の二軸モデルで<b>絞る方向にしか働かない</b>
 * （{@code visibility} 側の制約を緩めて公開に倒すことはできない）ため、
 * <b>未ログインでの閲覧はこの製品では構造的に成立しない</b>と判明した（実装時の実測で発覚）。
 * よって本クラスは「未ログインなら全 8 エンドポイントが例外なく 401」を固定する
 * （{@code min_view_role} の値によらない）。</p>
 *
 * <h2>{@code addFilters = false} を使わない理由</h2>
 * <p>他の契約 IT は {@code addFilters = false} で Spring Security のフィルタチェーンを外して
 * {@code SecurityContextHolder} を直接差し替えるため、<b>本来のフィルタ由来の認証要否を検証できない</b>。
 * 未ログインが正しく弾かれるかは、実フィルタチェーンを通さないと確かめられない
 * （金型: {@code ScheduleKeepUnauthenticatedContractIT}）。</p>
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

    /** min_view_role = ANYONE の予定（それでも未ログインでは読めない・§4.1 再訂正）。 */
    private Long anyoneScheduleId;
    /** min_view_role = MEMBER_PLUS の予定。 */
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
        commentOnAnyone = saveComment(anyoneScheduleId, ownerId, "非公開のコメント");
    }

    @Test
    @DisplayName("AC-15 min_view_role=ANYONE でも未ログインでは一覧 GET が 401（visibility 軸に公開値が無いため成立しない）")
    void AC15_ANYONEでも未ログインの一覧は401() throws Exception {
        mockMvc.perform(get(COMMENTS, anyoneScheduleId))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("AC-15 未ログインの POST は 401（403 ではない）")
    void AC15_未ログインのPOSTは401() throws Exception {
        mockMvc.perform(post(COMMENTS, anyoneScheduleId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"未ログイン投稿\",\"parentId\":null,\"mentionedUserIds\":null}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("AC-15 meta / replies / mention-candidates / PATCH / DELETE / settings は未ログインなら 401")
    void AC15_その他のエンドポイントも未ログインで401() throws Exception {
        mockMvc.perform(get(COMMENTS + "/meta", anyoneScheduleId))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get(COMMENT_BY_ID + "/replies", anyoneScheduleId, commentOnAnyone))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get(COMMENTS + "/mention-candidates", anyoneScheduleId))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(patch(COMMENT_BY_ID, anyoneScheduleId, commentOnAnyone)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"未ログイン編集\"}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(delete(COMMENT_BY_ID, anyoneScheduleId, commentOnAnyone))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(patch(COMMENTS + "/settings", anyoneScheduleId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commentsEnabled\":false}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("AC-15 min_view_role=MEMBER_PLUS の予定も未ログインでは一覧 GET が 401")
    void AC15_MEMBER_PLUSの一覧も未ログインで401() throws Exception {
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
