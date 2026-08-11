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
 * F03.16 予定コメントスレッド — スレッド本体（投稿・返信・編集・削除・一覧・境界）契約テスト（試練）。
 *
 * <p>設計書: {@code docs/features/F03.16_schedule_comment_thread.md} §4.2〜§4.4 / §5 / §9.1 / §9.3。
 * 対応 AC: AC-01・AC-02・AC-03・AC-03b・AC-04・AC-05・AC-06・AC-06b・AC-06c・AC-08・AC-11・
 * AC-16・AC-16b・AC-19・AC-21・AC-22・AC-26・AC-26b・AC-26c・AC-27・AC-27c・AC-28・AC-34。</p>
 *
 * <h2>この層で守らせる核心</h2>
 * <ul>
 *   <li><b>トゥームストーン述語</b> {@code deleted_at IS NULL OR (depth = 0 AND reply_count > 0)} を
 *       一覧クエリと件数クエリの<b>両方に同一条件で</b>適用すること（AC-26b）。片方だけだと
 *       「20件と言われたのに19件しか出ない」空席が生まれる。</li>
 *   <li><b>{@code writable()} 述語の一本化</b>: 「スレッドが閉じている」も「予定が中止」も
 *       同一の 409 {@code SCHEDULE_COMMENT_005} で返し、呼び分けない（AC-16 / AC-16b）。</li>
 *   <li><b>{@code reply_count} と実データの一致</b>（AC-27c）。乖離するとトゥームストーン表示が壊れる。</li>
 * </ul>
 *
 * <h2>秘匿系 404 でエラーコードまで検証する理由</h2>
 * <p>未実装の状態では素の 404 が返るため、ステータスのみの検証では偽の緑になる。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("F03.16 予定コメント スレッド契約テスト（試練）")
class ScheduleCommentThreadContractIT extends AbstractMySqlIntegrationTest {

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

    @MockitoBean
    private GoogleApiClient googleApiClient;

    @MockitoBean
    private GoogleCalendarWebhookService googleCalendarWebhookService;

    @PersistenceContext
    private EntityManager em;

    private Long teamId;
    private Long memberId;
    private Long otherMemberId;
    private Long teamAdminId;
    private Long scheduleId;

    @BeforeEach
    void setUp() {
        String nonce = String.valueOf(System.nanoTime());
        teamId = ScheduleCommentTestFixtures.insertTeam(em, "F0316 スレッド", "sct-team-" + nonce);

        memberId = ScheduleCommentTestFixtures.insertUser(em, "sct-member-" + nonce + "@example.com", "山田 太郎");
        otherMemberId = ScheduleCommentTestFixtures.insertUser(em, "sct-other-" + nonce + "@example.com", "鈴木 花子");
        teamAdminId = ScheduleCommentTestFixtures.insertUser(em, "sct-admin-" + nonce + "@example.com", "管理 四郎");

        MembershipTestHelper.insertMembership(em, memberId, ScopeType.TEAM, teamId, RoleKind.MEMBER);
        MembershipTestHelper.insertMembership(em, otherMemberId, ScopeType.TEAM, teamId, RoleKind.MEMBER);
        MembershipTestHelper.insertMembership(em, teamAdminId, ScopeType.TEAM, teamId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, teamAdminId, "ADMIN", teamId, null);

        em.flush();
        em.clear();

        scheduleId = saveSchedule(ScheduleStatus.SCHEDULED, true, teamAdminId);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-01 / AC-02: 投稿・返信
    // ═════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("AC-01 MEMBER の投稿は 201 で、DB に depth=0・parent_id/root_id が NULL・reply_count=0 の行が1件できる")
    void AC01_トップレベル投稿() throws Exception {
        setAuthentication(memberId);
        String response = mockMvc.perform(post(COMMENTS, scheduleId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(postBody("集合場所は駅前でよいですか？", null, null)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.depth").value(0))
                .andExpect(jsonPath("$.data.parentId").doesNotExist())
                .andExpect(jsonPath("$.data.rootId").doesNotExist())
                .andExpect(jsonPath("$.data.isEdited").value(false))
                .andExpect(jsonPath("$.data.isDeleted").value(false))
                .andExpect(jsonPath("$.data.replyCount").value(0))
                .andExpect(jsonPath("$.data.scheduleId").value(scheduleId))
                .andExpect(jsonPath("$.data.author.userId").value(memberId))
                .andExpect(jsonPath("$.data.author.displayName").value("山田 太郎"))
                .andExpect(jsonPath("$.data.canEdit").value(true))
                .andExpect(jsonPath("$.data.canDelete").value(true))
                .andReturn().getResponse().getContentAsString();

        em.flush();
        em.clear();
        UUID createdId = UUID.fromString(objectMapper.readTree(response).path("data").path("id").asText());
        ScheduleCommentEntity persisted = scheduleCommentRepository.findById(createdId).orElseThrow();
        assertThat(persisted.getScheduleId()).isEqualTo(scheduleId);
        assertThat(persisted.getUserId()).isEqualTo(memberId);
        assertThat(persisted.getParentId()).isNull();
        assertThat(persisted.getRootId()).isNull();
        assertThat(persisted.getDepth()).isZero();
        assertThat(persisted.getIsEdited()).isFalse();
        assertThat(persisted.getReplyCount()).isZero();
    }

    @Test
    @DisplayName("AC-02 返信は depth=1・parent_id=root_id=親ID で作られ、親行の reply_count が 1 になる")
    void AC02_返信と親カウンタの増分() throws Exception {
        UUID parentId = saveComment(memberId, "集合場所は？", null);

        setAuthentication(otherMemberId);
        String response = mockMvc.perform(post(COMMENTS, scheduleId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(postBody("駅前で大丈夫です！", parentId, null)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.depth").value(1))
                .andExpect(jsonPath("$.data.parentId").value(parentId.toString()))
                .andExpect(jsonPath("$.data.rootId").value(parentId.toString()))
                .andReturn().getResponse().getContentAsString();

        em.flush();
        em.clear();
        assertThat(scheduleCommentRepository.findById(parentId).orElseThrow().getReplyCount())
                .as("返信の作成で親の reply_count が増えないとトゥームストーン判定が壊れる")
                .isEqualTo(1);
        assertThat(objectMapper.readTree(response).path("data").path("replyCount").asInt())
                .as("返信行の replyCount は常に 0")
                .isZero();
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-03 / AC-03b: 一覧の階層・ソート契約
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("一覧の階層とソート契約")
    class ListContract {

        @Test
        @DisplayName("AC-03 一覧はトップレベルを createdAt 昇順で返し、返信行の replies は常に null（無限ネストしない）")
        void AC03_階層と昇順() throws Exception {
            UUID first = saveComment(memberId, "1番目", null);
            saveReply(first, otherMemberId, "1番目への返信");
            saveComment(memberId, "2番目", null);

            setAuthentication(memberId);
            mockMvc.perform(get(COMMENTS, scheduleId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[0].body").value("1番目"))
                    .andExpect(jsonPath("$.data[1].body").value("2番目"))
                    .andExpect(jsonPath("$.data[0].replies[0].body").value("1番目への返信"))
                    .andExpect(jsonPath("$.data[0].replies[0].depth").value(1))
                    .andExpect(jsonPath("$.data[0].replies[0].replies").doesNotExist())
                    .andExpect(jsonPath("$.meta.page").value(0))
                    .andExpect(jsonPath("$.meta.total").value(2));
        }

        @Test
        @DisplayName("AC-03 一覧応答にスレッド状態（commentsEnabled / canPost）は含まれない（PageMeta は固定4フィールド）")
        void AC03_一覧のmetaは固定4フィールド() throws Exception {
            saveComment(memberId, "本文", null);

            setAuthentication(memberId);
            mockMvc.perform(get(COMMENTS, scheduleId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.meta.commentsEnabled").doesNotExist())
                    .andExpect(jsonPath("$.meta.canPost").doesNotExist())
                    .andExpect(jsonPath("$.meta.hasNext").doesNotExist())
                    .andExpect(jsonPath("$.meta.totalElements").doesNotExist())
                    .andExpect(jsonPath("$.meta.totalPages").exists())
                    .andExpect(jsonPath("$.meta.size").exists());
        }

        @Test
        @DisplayName("AC-03b sort=createdAt,desc はトップレベルのみ逆順にし、replies は依然として createdAt 昇順のまま")
        void AC03b_descでも返信は昇順() throws Exception {
            // 「新しい順」を返信にも波及させると、会話の途中3件が逆順に並び文意が読めなくなる。
            UUID first = saveComment(memberId, "1番目", null);
            saveReply(first, otherMemberId, "返信A");
            saveReply(first, otherMemberId, "返信B");
            saveComment(memberId, "2番目", null);

            setAuthentication(memberId);
            mockMvc.perform(get(COMMENTS, scheduleId).param("sort", "createdAt,desc"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[0].body").value("2番目"))
                    .andExpect(jsonPath("$.data[1].body").value("1番目"))
                    .andExpect(jsonPath("$.data[1].replies[0].body").value("返信A"))
                    .andExpect(jsonPath("$.data[1].replies[1].body").value("返信B"));
        }

        @Test
        @DisplayName("AC-11 自分のコメントは canEdit/canDelete が true、他人のコメントは（権限なし MEMBER 視点で）両方 false")
        void AC11_編集削除フラグ() throws Exception {
            saveComment(memberId, "自分の発言", null);
            saveComment(otherMemberId, "他人の発言", null);

            setAuthentication(memberId);
            mockMvc.perform(get(COMMENTS, scheduleId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[?(@.body == '自分の発言')].canEdit").value(true))
                    .andExpect(jsonPath("$.data[?(@.body == '自分の発言')].canDelete").value(true))
                    .andExpect(jsonPath("$.data[?(@.body == '他人の発言')].canEdit").value(false))
                    .andExpect(jsonPath("$.data[?(@.body == '他人の発言')].canDelete").value(false));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-04 / AC-05 / AC-06 / AC-06b / AC-06c / AC-19: 編集・削除・トゥームストーン
    // ═════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("AC-04 本人の編集で本文が更新され isEdited=true になる（編集前の本文はどの API からも取得できない）")
    void AC04_編集() throws Exception {
        UUID commentId = saveComment(memberId, "編集前の本文", null);

        setAuthentication(memberId);
        mockMvc.perform(patch(COMMENT_BY_ID, scheduleId, commentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"編集後の本文\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.body").value("編集後の本文"))
                .andExpect(jsonPath("$.data.isEdited").value(true));

        String listed = mockMvc.perform(get(COMMENTS, scheduleId))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(listed)
                .as("編集履歴を持たない設計なので、編集前の本文が応答に現れてはならない")
                .doesNotContain("編集前の本文");
    }

    @Test
    @DisplayName("AC-05 返信のないコメントを本人が削除すると 204 で、一覧から完全に消える")
    void AC05_返信なしの削除は一覧から消える() throws Exception {
        UUID commentId = saveComment(memberId, "消えるコメント", null);

        setAuthentication(memberId);
        mockMvc.perform(delete(COMMENT_BY_ID, scheduleId, commentId))
                .andExpect(status().isNoContent());

        mockMvc.perform(get(COMMENTS, scheduleId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty())
                .andExpect(jsonPath("$.meta.total").value(0));

        em.flush();
        em.clear();
        assertThat(scheduleCommentRepository.findById(commentId).orElseThrow().getDeletedAt())
                .as("物理削除ではなく deleted_at による論理削除であること（原則3）")
                .isNotNull();
    }

    @Test
    @DisplayName("AC-06 生存返信のあるトップレベルを削除するとトゥームストーンが残り、body と author が null になる")
    void AC06_トゥームストーン() throws Exception {
        UUID parentId = saveComment(memberId, "消される親コメント", null);
        saveReply(parentId, otherMemberId, "残る返信");

        setAuthentication(memberId);
        mockMvc.perform(delete(COMMENT_BY_ID, scheduleId, parentId))
                .andExpect(status().isNoContent());

        String listed = mockMvc.perform(get(COMMENTS, scheduleId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.total").value(1))
                .andExpect(jsonPath("$.data[0].isDeleted").value(true))
                .andExpect(jsonPath("$.data[0].body").doesNotExist())
                .andExpect(jsonPath("$.data[0].author").doesNotExist())
                .andExpect(jsonPath("$.data[0].replies[0].body").value("残る返信"))
                .andReturn().getResponse().getContentAsString();

        assertThat(listed)
                .as("削除された本文を BE から一切送らない（FE で隠す対処療法にしない）")
                .doesNotContain("消される親コメント");
    }

    @Test
    @DisplayName("AC-06b トゥームストーンの返信をすべて削除すると reply_count が 0 になり、トップレベルも一覧から消える")
    void AC06b_トゥームストーンの消滅() throws Exception {
        UUID parentId = saveComment(memberId, "親", null);
        UUID replyId = saveReply(parentId, otherMemberId, "唯一の返信");

        setAuthentication(memberId);
        mockMvc.perform(delete(COMMENT_BY_ID, scheduleId, parentId)).andExpect(status().isNoContent());

        setAuthentication(otherMemberId);
        mockMvc.perform(delete(COMMENT_BY_ID, scheduleId, replyId)).andExpect(status().isNoContent());

        setAuthentication(memberId);
        mockMvc.perform(get(COMMENTS, scheduleId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty())
                .andExpect(jsonPath("$.meta.total").value(0));

        em.flush();
        em.clear();
        assertThat(scheduleCommentRepository.findById(parentId).orElseThrow().getReplyCount())
                .as("返信の論理削除で親の reply_count がデクリメントされること（生存返信数の定義）")
                .isZero();
    }

    @Test
    @DisplayName("AC-06c トゥームストーン（削除済みトップレベル）の replies は 404 ではなく 200 で全件返る")
    void AC06c_トゥームストーン親の返信取得() throws Exception {
        // 親が消えていても返信は読めなければならない（トゥームストーンを残す目的がまさにこれ）。
        UUID parentId = saveComment(memberId, "親", null);
        saveReply(parentId, otherMemberId, "返信1");
        saveReply(parentId, otherMemberId, "返信2");

        setAuthentication(memberId);
        mockMvc.perform(delete(COMMENT_BY_ID, scheduleId, parentId)).andExpect(status().isNoContent());

        mockMvc.perform(get(COMMENT_BY_ID + "/replies", scheduleId, parentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.total").value(2))
                .andExpect(jsonPath("$.data[0].body").value("返信1"))
                .andExpect(jsonPath("$.data[1].body").value("返信2"));
    }

    @Test
    @DisplayName("AC-19 削除済みコメントへの PATCH / DELETE は 404 SCHEDULE_COMMENT_003")
    void AC19_削除済みへの操作は404() throws Exception {
        UUID commentId = saveComment(memberId, "削除される", null);

        setAuthentication(memberId);
        mockMvc.perform(delete(COMMENT_BY_ID, scheduleId, commentId)).andExpect(status().isNoContent());

        mockMvc.perform(patch(COMMENT_BY_ID, scheduleId, commentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"復活させたい\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("SCHEDULE_COMMENT_003"));
        mockMvc.perform(delete(COMMENT_BY_ID, scheduleId, commentId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("SCHEDULE_COMMENT_003"));
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-08 / AC-16 / AC-16b: スレッド開閉と writable() 述語の一本化
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("writable() 述語（comments_enabled AND status != CANCELLED）")
    class WritablePredicate {

        @Test
        @DisplayName("AC-08 作成者が締め切ると commentsEnabled=false になり、meta.canPost=false / canPostReason=CLOSED だが既存コメントは読める")
        void AC08_スレッドを締め切っても閲覧はできる() throws Exception {
            saveComment(memberId, "締切前の発言", null);

            setAuthentication(teamAdminId);
            mockMvc.perform(patch(COMMENTS + "/settings", scheduleId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"commentsEnabled\":false}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.commentsEnabled").value(false));

            setAuthentication(memberId);
            mockMvc.perform(get(COMMENTS + "/meta", scheduleId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.commentsEnabled").value(false))
                    .andExpect(jsonPath("$.data.canPost").value(false))
                    .andExpect(jsonPath("$.data.canPostReason").value("CLOSED"));

            mockMvc.perform(get(COMMENTS, scheduleId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[0].body").value("締切前の発言"));
        }

        @Test
        @DisplayName("AC-16 comments_enabled=FALSE の予定への POST は 409 SCHEDULE_COMMENT_005、同じ状態の DELETE は 204 で成功する")
        void AC16_閉じたスレッド() throws Exception {
            // 閉じるのは «新しい会話» であって «モデレーション» ではない（閉じたら消せないは運用上の詰み）。
            Long closed = saveSchedule(ScheduleStatus.SCHEDULED, false, teamAdminId);
            UUID existing = saveComment(closed, memberId, "閉じる前の発言", null);

            setAuthentication(memberId);
            mockMvc.perform(post(COMMENTS, closed)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(postBody("閉じた後の投稿", null, null)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value("SCHEDULE_COMMENT_005"));

            mockMvc.perform(delete(COMMENT_BY_ID, closed, existing))
                    .andExpect(status().isNoContent());
        }

        @Test
        @DisplayName("AC-16b status=CANCELLED の予定は POST/PATCH が AC-16 と同一の 409 SCHEDULE_COMMENT_005、GET は 200、DELETE は 204")
        void AC16b_中止された予定() throws Exception {
            // 「相当」ではなく同一のコード・同一のステータスであること（述語の一本化）。
            Long cancelled = saveSchedule(ScheduleStatus.CANCELLED, true, teamAdminId);
            UUID existing = saveComment(cancelled, memberId, "中止前の発言", null);

            setAuthentication(memberId);
            mockMvc.perform(post(COMMENTS, cancelled)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(postBody("中止後の投稿", null, null)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value("SCHEDULE_COMMENT_005"));

            mockMvc.perform(patch(COMMENT_BY_ID, cancelled, existing)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"body\":\"中止後の編集\"}"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value("SCHEDULE_COMMENT_005"));

            mockMvc.perform(get(COMMENTS, cancelled)).andExpect(status().isOk());
            mockMvc.perform(delete(COMMENT_BY_ID, cancelled, existing)).andExpect(status().isNoContent());
        }

        @Test
        @DisplayName("AC-16b canPostReason は CANCELLED を CLOSED より先に返す（両方該当時の順序固定）")
        void AC16b_canPostReasonの判定順序() throws Exception {
            Long both = saveSchedule(ScheduleStatus.CANCELLED, false, teamAdminId);

            setAuthentication(memberId);
            mockMvc.perform(get(COMMENTS + "/meta", both))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.canPost").value(false))
                    .andExpect(jsonPath("$.data.canPostReason").value("CANCELLED"));
        }

        @Test
        @DisplayName("投稿できる状態では meta.canPost=true かつ canPostReason は null")
        void 投稿可能ならcanPostReasonはnull() throws Exception {
            setAuthentication(memberId);
            mockMvc.perform(get(COMMENTS + "/meta", scheduleId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.commentsEnabled").value(true))
                    .andExpect(jsonPath("$.data.canPost").value(true))
                    .andExpect(jsonPath("$.data.canPostReason").doesNotExist());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-21 / AC-22: 境界（本文長・深さ）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("境界値")
    class Boundaries {

        @Test
        @DisplayName("AC-21 本文 2000 文字ちょうどは 201、2001 文字は 400 SCHEDULE_COMMENT_001")
        void AC21_本文長の境界() throws Exception {
            setAuthentication(memberId);
            mockMvc.perform(post(COMMENTS, scheduleId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(postBody("あ".repeat(2000), null, null)))
                    .andExpect(status().isCreated());

            mockMvc.perform(post(COMMENTS, scheduleId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(postBody("あ".repeat(2001), null, null)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("SCHEDULE_COMMENT_001"));
        }

        @Test
        @DisplayName("AC-21 空文字・空白のみ（トリム後に空）の本文は 400 SCHEDULE_COMMENT_001")
        void AC21_空本文は400() throws Exception {
            setAuthentication(memberId);
            mockMvc.perform(post(COMMENTS, scheduleId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(postBody("", null, null)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("SCHEDULE_COMMENT_001"));

            mockMvc.perform(post(COMMENTS, scheduleId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(postBody("   　  ", null, null)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("SCHEDULE_COMMENT_001"));
        }

        @Test
        @DisplayName("AC-22 depth=1 のコメントへの返信は 400 SCHEDULE_COMMENT_007 で、返信先を根へ自動で付け替えない")
        void AC22_深さ上限() throws Exception {
            // 自動付け替えはユーザーが意図した相手と表示上の親がずれる事故になる（症状を隠さない）。
            UUID parentId = saveComment(memberId, "親", null);
            UUID replyId = saveReply(parentId, otherMemberId, "返信");
            long before = ScheduleCommentTestFixtures.countComments(em, scheduleId);

            setAuthentication(memberId);
            mockMvc.perform(post(COMMENTS, scheduleId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(postBody("返信への返信", replyId, null)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("SCHEDULE_COMMENT_007"));

            em.flush();
            em.clear();
            assertThat(ScheduleCommentTestFixtures.countComments(em, scheduleId))
                    .as("拒否されたのにコメントが作られていては «自動付け替えしない» が守られていない")
                    .isEqualTo(before);
        }

        @Test
        @DisplayName("AC-22 別予定のコメントを parentId に指定すると 404 SCHEDULE_COMMENT_003（他予定への返信を作らせない）")
        void AC22_別予定のコメントへの返信は拒否される() throws Exception {
            Long otherSchedule = saveSchedule(ScheduleStatus.SCHEDULED, true, teamAdminId);
            UUID foreignParent = saveComment(otherSchedule, memberId, "別予定のコメント", null);

            setAuthentication(memberId);
            mockMvc.perform(post(COMMENTS, scheduleId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(postBody("越境返信", foreignParent, null)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("SCHEDULE_COMMENT_003"));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-26 / AC-26b / AC-26c: ページング
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("ページング契約")
    class Paging {

        @Test
        @DisplayName("AC-26 トップレベル21件で page=0&size=20 は 20件・total=21・totalPages=2、page=1 で残り1件。size=100 は 50 にクランプされる")
        void AC26_ページング境界() throws Exception {
            for (int i = 0; i < 21; i++) {
                saveComment(memberId, "コメント" + i, null);
            }

            setAuthentication(memberId);
            mockMvc.perform(get(COMMENTS, scheduleId).param("page", "0").param("size", "20"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(20))
                    .andExpect(jsonPath("$.meta.total").value(21))
                    .andExpect(jsonPath("$.meta.totalPages").value(2));

            mockMvc.perform(get(COMMENTS, scheduleId).param("page", "1").param("size", "20"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(1))
                    .andExpect(jsonPath("$.meta.page").value(1));

            mockMvc.perform(get(COMMENTS, scheduleId).param("size", "100"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.meta.size").value(50));
        }

        @Test
        @DisplayName("AC-26b トゥームストーン述語は一覧と件数の両方へ同一条件で効く（削除済み生存返信0を除外・トゥームストーンを算入）")
        void AC26b_トゥームストーン述語とページングの整合() throws Exception {
            // 片方だけに条件を書くと «20件と言われたのに19件しか表示されない» 空席が生まれる。
            for (int i = 0; i < 19; i++) {
                saveComment(memberId, "生存" + i, null);
            }
            UUID orphan = saveComment(memberId, "削除済み・返信なし", null);
            UUID tombstone = saveComment(memberId, "削除済み・返信あり", null);
            saveReply(tombstone, otherMemberId, "生き残る返信");

            setAuthentication(memberId);
            mockMvc.perform(delete(COMMENT_BY_ID, scheduleId, orphan)).andExpect(status().isNoContent());
            mockMvc.perform(delete(COMMENT_BY_ID, scheduleId, tombstone)).andExpect(status().isNoContent());

            mockMvc.perform(get(COMMENTS, scheduleId).param("page", "0").param("size", "20"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.meta.total").value(20))
                    .andExpect(jsonPath("$.meta.totalPages").value(1))
                    .andExpect(jsonPath("$.data.length()").value(20));
        }

        @Test
        @DisplayName("AC-26c created_at が完全に同一の3件をページ送りしても重複・取りこぼしが起きない（id 昇順の tie-break）")
        void AC26c_同着の順序安定() throws Exception {
            LocalDateTime sameInstant = LocalDateTime.of(2026, 9, 10, 9, 0, 0);
            List<UUID> ids = List.of(
                    saveCommentAt(memberId, "同着A", sameInstant),
                    saveCommentAt(memberId, "同着B", sameInstant),
                    saveCommentAt(memberId, "同着C", sameInstant));

            setAuthentication(memberId);
            List<String> firstPage = collectIds(get(COMMENTS, scheduleId).param("page", "0").param("size", "2"));
            List<String> secondPage = collectIds(get(COMMENTS, scheduleId).param("page", "1").param("size", "2"));

            assertThat(firstPage).hasSize(2);
            assertThat(secondPage).hasSize(1);
            assertThat(firstPage)
                    .as("ORDER BY に一意列を含めないとページ間で行が重複する")
                    .doesNotContainAnyElementsOf(secondPage);
            assertThat(firstPage.size() + secondPage.size())
                    .as("取りこぼしがあってはならない")
                    .isEqualTo(ids.size());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-27 / AC-27c: 返信の同梱境界とカウンタの一致
    // ═════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("AC-27 返信が5件あるトップレベルは replies に3件同梱・replyCount=5 で返り、replies エンドポイントでは5件全部取れる")
    void AC27_返信の同梱境界() throws Exception {
        UUID parentId = saveComment(memberId, "親", null);
        for (int i = 1; i <= 5; i++) {
            saveReply(parentId, otherMemberId, "返信" + i);
        }

        setAuthentication(memberId);
        mockMvc.perform(get(COMMENTS, scheduleId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].replyCount").value(5))
                .andExpect(jsonPath("$.data[0].replies.length()").value(3))
                // 同梱するのは «最新3件» を createdAt 昇順で並べたもの（続きの会話が見えた方が文脈を追える）。
                .andExpect(jsonPath("$.data[0].replies[0].body").value("返信3"))
                .andExpect(jsonPath("$.data[0].replies[2].body").value("返信5"));

        mockMvc.perform(get(COMMENT_BY_ID + "/replies", scheduleId, parentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(5))
                .andExpect(jsonPath("$.meta.total").value(5));
    }

    @Test
    @DisplayName("AC-27c 返信5件から2件削除すると replyCount=3 と replies の meta.total=3 が一致し、1件追加すると両方 4 になる")
    void AC27c_カウンタと実データの一致() throws Exception {
        // 非正規化カウンタが実データから乖離すると、消えるべき行が残り／残すべき行が消える。
        UUID parentId = saveComment(memberId, "親", null);
        List<UUID> replies = List.of(
                saveReply(parentId, otherMemberId, "返信1"),
                saveReply(parentId, otherMemberId, "返信2"),
                saveReply(parentId, otherMemberId, "返信3"),
                saveReply(parentId, otherMemberId, "返信4"),
                saveReply(parentId, otherMemberId, "返信5"));

        setAuthentication(otherMemberId);
        mockMvc.perform(delete(COMMENT_BY_ID, scheduleId, replies.get(0))).andExpect(status().isNoContent());
        mockMvc.perform(delete(COMMENT_BY_ID, scheduleId, replies.get(1))).andExpect(status().isNoContent());

        setAuthentication(memberId);
        mockMvc.perform(get(COMMENTS, scheduleId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].replyCount").value(3));
        mockMvc.perform(get(COMMENT_BY_ID + "/replies", scheduleId, parentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.total").value(3));

        mockMvc.perform(post(COMMENTS, scheduleId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(postBody("追加の返信", parentId, null)))
                .andExpect(status().isCreated());

        mockMvc.perform(get(COMMENTS, scheduleId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].replyCount").value(4));
        mockMvc.perform(get(COMMENT_BY_ID + "/replies", scheduleId, parentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.total").value(4));
    }

    @Test
    @DisplayName("AC-27 replies エンドポイントに depth=1 のコメント ID を渡すと 400 SCHEDULE_COMMENT_006")
    void AC27_返信に対するreplies取得は400() throws Exception {
        UUID parentId = saveComment(memberId, "親", null);
        UUID replyId = saveReply(parentId, otherMemberId, "返信");

        setAuthentication(memberId);
        mockMvc.perform(get(COMMENT_BY_ID + "/replies", scheduleId, replyId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("SCHEDULE_COMMENT_006"));
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-28: 退会・匿名化
    // ═════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("AC-28 投稿者が匿名化（user_id が NULL）された後も本文は残り、author が null で返る（空欄・NPE にならない）")
    void AC28_退会ユーザーのコメント() throws Exception {
        UUID commentId = saveComment(otherMemberId, "退会者の発言", null);

        em.createNativeQuery("UPDATE schedule_comments SET user_id = NULL WHERE id = :id")
                .setParameter("id", uuidToBytes(commentId))
                .executeUpdate();
        em.flush();
        em.clear();

        setAuthentication(memberId);
        mockMvc.perform(get(COMMENTS, scheduleId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].body").value("退会者の発言"))
                .andExpect(jsonPath("$.data[0].author").doesNotExist())
                .andExpect(jsonPath("$.data[0].canEdit").value(false))
                .andExpect(jsonPath("$.data[0].canDelete").value(false));
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-34: deleted_at の明示条件（@SQLRestriction を使わない方針の担保）
    // ═════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("AC-34 削除済み行を1件混ぜた状態で、リポジトリの各メソッドが削除済みを含む／含まないを意図どおりに扱う")
    void AC34_削除済み行に対するリポジトリの挙動を固定する() {
        // @SQLRestriction を付けない方針を採る以上、「条件を書き忘れたら削除済みが漏れる」。
        // 各メソッドの挙動をここで固定し、メソッド追加時に行を足し忘れれば気づけるようにする。
        UUID alive = saveComment(memberId, "生存", null);
        UUID deleted = saveComment(memberId, "削除済み", null);
        em.createNativeQuery("UPDATE schedule_comments SET deleted_at = NOW() WHERE id = :id")
                .setParameter("id", uuidToBytes(deleted))
                .executeUpdate();
        em.flush();
        em.clear();

        assertThat(scheduleCommentRepository.findByIdAndScheduleId(deleted, scheduleId))
                .as("findByIdAndScheduleId は削除済みも «含む»（削除済みへの操作を 404 と判定するために必要）")
                .isPresent();
        assertThat(scheduleCommentRepository.findByIdAndScheduleIdAndDeletedAtIsNull(deleted, scheduleId))
                .as("...AndDeletedAtIsNull は削除済みを «含まない»（返信の親として使えない）")
                .isEmpty();
        assertThat(scheduleCommentRepository.findByIdAndScheduleIdAndDeletedAtIsNull(alive, scheduleId))
                .isPresent();
        assertThat(scheduleCommentRepository.findVisibilityProjectionsByIdIn(List.of(alive, deleted)))
                .as("可視性射影は削除済みも «含む»（判定に削除有無は不要であり、"
                        + "落とすとトゥームストーンが可視性判定を通れなくなる）")
                .hasSize(2);

        assertThat(declaredRepositoryMethodNames())
                .as("ScheduleCommentRepository にメソッドを足したら、本テストにも "
                        + "«削除済みを含む／含まない» の期待を必ず1行足すこと")
                .containsExactlyInAnyOrder(
                        "findByIdAndScheduleId",
                        "findByIdAndScheduleIdAndDeletedAtIsNull",
                        "findVisibilityProjectionsByIdIn");
    }

    // ═════════════════════════════════════════════════════════════════════
    // ヘルパー
    // ═════════════════════════════════════════════════════════════════════

    private List<String> declaredRepositoryMethodNames() {
        return java.util.Arrays.stream(ScheduleCommentRepository.class.getDeclaredMethods())
                .map(java.lang.reflect.Method::getName)
                .distinct()
                .toList();
    }

    private List<String> collectIds(org.springframework.test.web.servlet.RequestBuilder request) throws Exception {
        String json = mockMvc.perform(request).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        List<String> ids = new java.util.ArrayList<>();
        objectMapper.readTree(json).path("data").forEach(node -> ids.add(node.path("id").asText()));
        return ids;
    }

    private String postBody(String body, UUID parentId, List<Long> mentionedUserIds) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("body", body);
        payload.put("parentId", parentId == null ? null : parentId.toString());
        payload.put("mentionedUserIds", mentionedUserIds);
        return objectMapper.writeValueAsString(payload);
    }

    private Long saveSchedule(ScheduleStatus status, boolean commentsEnabled, Long createdBy) {
        Long id = scheduleRepository.save(ScheduleEntity.builder()
                .teamId(teamId)
                .title("F0316 スレッド予定")
                .startAt(LocalDateTime.of(2026, 9, 10, 10, 0))
                .endAt(LocalDateTime.of(2026, 9, 10, 12, 0))
                .eventType(EventType.PRACTICE)
                .visibility(ScheduleVisibility.MEMBERS_ONLY)
                .minViewRole(MinViewRole.MEMBER_PLUS)
                .status(status)
                .commentsEnabled(commentsEnabled)
                .attendanceRequired(true)
                .allowProxyAttendance(true)
                .isProxyAutoAccept(false)
                .createdBy(createdBy)
                .build()).getId();
        em.flush();
        em.clear();
        return id;
    }

    private UUID saveComment(Long authorId, String body, UUID parentId) {
        return saveComment(scheduleId, authorId, body, parentId);
    }

    private UUID saveComment(Long targetScheduleId, Long authorId, String body, UUID parentId) {
        ScheduleCommentEntity saved = scheduleCommentRepository.save(ScheduleCommentEntity.builder()
                .scheduleId(targetScheduleId)
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

    /** created_at を明示指定して保存する（同着の順序検証用）。日時は必ず LocalDateTime を bind する。 */
    private UUID saveCommentAt(Long authorId, String body, LocalDateTime createdAt) {
        UUID id = saveComment(authorId, body, null);
        em.createNativeQuery("UPDATE schedule_comments SET created_at = :ts WHERE id = :id")
                .setParameter("ts", createdAt)
                .setParameter("id", uuidToBytes(id))
                .executeUpdate();
        em.flush();
        em.clear();
        return id;
    }

    /** 返信を保存し、親の reply_count も併せて整合させる（フィクスチャが実データと矛盾しないようにする）。 */
    private UUID saveReply(UUID parentId, Long authorId, String body) {
        ScheduleCommentEntity saved = scheduleCommentRepository.save(ScheduleCommentEntity.builder()
                .scheduleId(scheduleId)
                .userId(authorId)
                .body(body)
                .parentId(parentId)
                .rootId(parentId)
                .depth(1)
                .build());
        ScheduleCommentEntity parent = scheduleCommentRepository.findById(parentId).orElseThrow();
        parent.incrementReplyCount();
        scheduleCommentRepository.save(parent);
        em.flush();
        em.clear();
        return saved.getId();
    }

    /** BINARY(16) 列へ bind するため UUID をビッグエンディアン 16 バイトへ変換する。 */
    private static byte[] uuidToBytes(UUID uuid) {
        java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate(16);
        buffer.putLong(uuid.getMostSignificantBits());
        buffer.putLong(uuid.getLeastSignificantBits());
        return buffer.array();
    }

    private void setAuthentication(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
    }
}
