package com.mannschaft.app.village.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.common.storage.R2StorageService;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.village.entity.VillageEntity;
import com.mannschaft.app.village.entity.VillageMeetupCommentEntity;
import com.mannschaft.app.village.entity.VillageMeetupEntity;
import com.mannschaft.app.village.entity.VillageMeetupTodoEntity;
import com.mannschaft.app.village.entity.VillageMembershipEntity;
import com.mannschaft.app.village.entity.enums.VillageJoinPolicy;
import com.mannschaft.app.village.entity.enums.VillageMeetupStatus;
import com.mannschaft.app.village.entity.enums.VillageRole;
import com.mannschaft.app.village.entity.enums.VillageSubjectType;
import com.mannschaft.app.village.entity.enums.VillageType;
import com.mannschaft.app.village.entity.enums.VillageVisibility;
import com.mannschaft.app.village.repository.VillageMeetupAttendanceRepository;
import com.mannschaft.app.village.repository.VillageMeetupCommentRepository;
import com.mannschaft.app.village.repository.VillageMeetupRepository;
import com.mannschaft.app.village.repository.VillageMeetupTodoRepository;
import com.mannschaft.app.village.repository.VillageMembershipRepository;
import com.mannschaft.app.village.repository.VillageRepository;
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

import java.time.LocalDate;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F17.2 Wave1 ②寄合の後半戦 — API 契約テスト（試練 / red 先行）。
 *
 * <h2>本テストの性質（重要 / 検分時に必読）</h2>
 *
 * <p><strong>red テスト</strong>であり、現時点（骨格のみ・Service/Controller 未実装）では
 * すべて失敗する。試練→出陣の TDD 土台であり、出陣（実装）後に green 化する。</p>
 *
 * <p>方式: 設計書 §4 の URL 文字列を MockMvc で直接叩く。未実装 EP は
 * {@code NoResourceFoundException} により 404 + {@code COMMON_005} を返すため、
 * 目標契約（200/201/204 や {@code VILLAGE_094} 等）を突きつける本テストは必ず赤くなる。
 * 出陣後は同じ URL にハンドラが結線され、契約どおりの応答で green 化する。</p>
 *
 * <p>金型: {@link com.mannschaft.app.circulation.CirculationWriteAclScopeContractIT}
 * （{@code AbstractMySqlIntegrationTest} + {@code @AutoConfigureMockMvc(addFilters=false)}）／
 * フィクスチャ流儀は {@link VillageCalendarControllerIntegrationTest}（村・membership を Repository で作る）。</p>
 *
 * <p>受け入れ条件（設計書 §11.2）: AC-07〜AC-13。加えてマスター御下命の
 * IDOR（村メンバーシップガード・クロス村 404）を明示検証する。</p>
 *
 * <p>DB 検証は骨格 Repository（既存）越しに行い、応答 DTO の項目名に過度に依存しない。
 * 日時フィクスチャは {@code LocalDateTime} で bind し、文字列リテラルの TZ 境界事故を避ける
 * （memory {@code feedback_it_fixture_datetime_tz_bind}）。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("F17.2 Wave1 ②寄合後半戦 API 契約テスト（試練・red）")
class VillageMeetupSecondHalfContractIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private VillageRepository villageRepository;

    @Autowired
    private VillageMembershipRepository membershipRepository;

    @Autowired
    private VillageMeetupRepository meetupRepository;

    @Autowired
    private VillageMeetupCommentRepository commentRepository;

    @Autowired
    private VillageMeetupTodoRepository todoRepository;

    @Autowired
    private VillageMeetupAttendanceRepository attendanceRepository;

    /** 署名 URL 計算は外部境界。決定論のため mock 化（本クラス群のコンテキスト構成を揃えて共有する）。 */
    @MockitoBean
    private R2StorageService r2StorageService;

    private static final Long HEADMAN_ID = 17_200_001L;   // 村長
    private static final Long ELDER_ID = 17_200_002L;      // 長老
    private static final Long ORGANIZER_ID = 17_200_003L;  // 幹事（寄合の organizer）
    private static final Long VILLAGER_ID = 17_200_004L;   // 一般村人
    private static final Long OTHER_VILLAGER_ID = 17_200_005L; // 別の一般村人（無関係）
    private static final Long OUTSIDER_ID = 17_200_006L;   // 村メンバーでない部外者

    @BeforeEach
    void setUp() {
        // 署名 URL は本クラスでは使わないが、姉妹契約テストとコンテキスト構成を揃えるため宣言のみ。
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ══════════════════════════════════════════════════════════════════════
    // AC-07: CONFIRMED 寄合の出欠 upsert（再送で UPDATE・1件のまま値が変わる）
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("AC-07 出欠 upsert（PUT .../attendance）")
    class Attendance {

        @Test
        @DisplayName("CONFIRMED 寄合で GOING→200、MAYBE 再送→200 かつ 1件のまま MAYBE に更新される")
        void upsert_going_then_maybe_updatesInPlace() throws Exception {
            VillageEntity v = persistVillage();
            persistMembership(v.getId(), VILLAGER_ID, VillageRole.VILLAGER);
            VillageMeetupEntity meetup = persistMeetup(v.getId(), ORGANIZER_ID, VillageMeetupStatus.CONFIRMED);

            authAs(VILLAGER_ID);
            mockMvc.perform(put("/api/v1/villages/{vid}/meetups/{mid}/attendance", v.getId(), meetup.getId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("status", "GOING"))))
                    .andExpect(status().isOk());

            mockMvc.perform(put("/api/v1/villages/{vid}/meetups/{mid}/attendance", v.getId(), meetup.getId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("status", "MAYBE"))))
                    .andExpect(status().isOk());

            // (meetup_id, user_id) UNIQUE により 1 行のまま status が MAYBE へ更新される
            assertThat(attendanceRepository.findByMeetupIdAndUserId(meetup.getId(), VILLAGER_ID))
                    .isPresent()
                    .get()
                    .satisfies(a -> assertThat(a.getStatus().name()).isEqualTo("MAYBE"));
        }

        // ── AC-08: PLANNING 状態では出欠を受け付けない ─────────────────────
        @Test
        @DisplayName("AC-08 PLANNING 状態の出欠は 400 系 + VILLAGE_094（MEETUP_NOT_CONFIRMED）")
        void planning_attendance_rejected() throws Exception {
            VillageEntity v = persistVillage();
            persistMembership(v.getId(), VILLAGER_ID, VillageRole.VILLAGER);
            VillageMeetupEntity meetup = persistMeetup(v.getId(), ORGANIZER_ID, VillageMeetupStatus.PLANNING);

            authAs(VILLAGER_ID);
            mockMvc.perform(put("/api/v1/villages/{vid}/meetups/{mid}/attendance", v.getId(), meetup.getId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("status", "GOING"))))
                    .andExpect(status().is4xxClientError())
                    .andExpect(jsonPath("$.error.code").value("VILLAGE_094"));
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // AC-09: コメント（POST 201 / GET 昇順 / 削除は本人＋村長/長老のみ）
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("AC-09 コメント（POST/GET/DELETE）")
    class Comments {

        @Test
        @DisplayName("村人のコメント投稿は 201 で永続化される")
        void post_comment_201() throws Exception {
            VillageEntity v = persistVillage();
            persistMembership(v.getId(), VILLAGER_ID, VillageRole.VILLAGER);
            VillageMeetupEntity meetup = persistMeetup(v.getId(), ORGANIZER_ID, VillageMeetupStatus.CONFIRMED);

            authAs(VILLAGER_ID);
            mockMvc.perform(post("/api/v1/villages/{vid}/meetups/{mid}/comments", v.getId(), meetup.getId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("body", "当日はよろしくお願いします"))))
                    .andExpect(status().isCreated());

            assertThat(commentRepository
                    .findByMeetupIdAndDeletedAtIsNullOrderByCreatedAtAsc(
                            meetup.getId(), org.springframework.data.domain.PageRequest.of(0, 20))
                    .getContent())
                    .hasSize(1);
        }

        @Test
        @DisplayName("コメント一覧は作成日昇順で返る（古い順）")
        void list_comments_ascending() throws Exception {
            VillageEntity v = persistVillage();
            persistMembership(v.getId(), VILLAGER_ID, VillageRole.VILLAGER);
            VillageMeetupEntity meetup = persistMeetup(v.getId(), ORGANIZER_ID, VillageMeetupStatus.CONFIRMED);
            persistComment(meetup.getId(), VILLAGER_ID, "古いコメント", LocalDateTime.of(2026, 7, 1, 9, 0));
            persistComment(meetup.getId(), VILLAGER_ID, "新しいコメント", LocalDateTime.of(2026, 7, 2, 9, 0));

            authAs(VILLAGER_ID);
            mockMvc.perform(get("/api/v1/villages/{vid}/meetups/{mid}/comments", v.getId(), meetup.getId()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[0].body").value("古いコメント"))
                    .andExpect(jsonPath("$.data[1].body").value("新しいコメント"));
        }

        @Test
        @DisplayName("投稿者本人はコメントを論理削除できる（204）")
        void author_deletes_own_comment_204() throws Exception {
            VillageEntity v = persistVillage();
            persistMembership(v.getId(), VILLAGER_ID, VillageRole.VILLAGER);
            VillageMeetupEntity meetup = persistMeetup(v.getId(), ORGANIZER_ID, VillageMeetupStatus.CONFIRMED);
            VillageMeetupCommentEntity c = persistComment(meetup.getId(), VILLAGER_ID, "消す",
                    LocalDateTime.of(2026, 7, 1, 9, 0));

            authAs(VILLAGER_ID);
            mockMvc.perform(delete("/api/v1/villages/{vid}/meetups/{mid}/comments/{cid}",
                            v.getId(), meetup.getId(), c.getId()))
                    .andExpect(status().isNoContent());

            assertThat(commentRepository.findById(c.getId())).get()
                    .satisfies(reloaded -> assertThat(reloaded.getDeletedAt()).isNotNull());
        }

        @Test
        @DisplayName("村長は他人のコメントを論理削除できる（204）")
        void headman_deletes_others_comment_204() throws Exception {
            VillageEntity v = persistVillage();
            persistMembership(v.getId(), VILLAGER_ID, VillageRole.VILLAGER);
            persistMembership(v.getId(), HEADMAN_ID, VillageRole.HEADMAN);
            VillageMeetupEntity meetup = persistMeetup(v.getId(), ORGANIZER_ID, VillageMeetupStatus.CONFIRMED);
            VillageMeetupCommentEntity c = persistComment(meetup.getId(), VILLAGER_ID, "村長が消す",
                    LocalDateTime.of(2026, 7, 1, 9, 0));

            authAs(HEADMAN_ID);
            mockMvc.perform(delete("/api/v1/villages/{vid}/meetups/{mid}/comments/{cid}",
                            v.getId(), meetup.getId(), c.getId()))
                    .andExpect(status().isNoContent());
        }

        @Test
        @DisplayName("第三者の一般村人は他人のコメントを削除できない（403）")
        void third_party_cannot_delete_comment_403() throws Exception {
            VillageEntity v = persistVillage();
            persistMembership(v.getId(), VILLAGER_ID, VillageRole.VILLAGER);
            persistMembership(v.getId(), OTHER_VILLAGER_ID, VillageRole.VILLAGER);
            VillageMeetupEntity meetup = persistMeetup(v.getId(), ORGANIZER_ID, VillageMeetupStatus.CONFIRMED);
            VillageMeetupCommentEntity c = persistComment(meetup.getId(), VILLAGER_ID, "守られるコメント",
                    LocalDateTime.of(2026, 7, 1, 9, 0));

            authAs(OTHER_VILLAGER_ID);
            mockMvc.perform(delete("/api/v1/villages/{vid}/meetups/{mid}/comments/{cid}",
                            v.getId(), meetup.getId(), c.getId()))
                    .andExpect(status().isForbidden());

            assertThat(commentRepository.findById(c.getId())).get()
                    .satisfies(reloaded -> assertThat(reloaded.getDeletedAt()).isNull());
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // AC-10/11/12: 宿題 TODO（claim / complete / release）
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("AC-10 宿題 claim（手挙げ）")
    class TodoClaim {

        @Test
        @DisplayName("未割当 TODO を村人本人が claim できる（200・自分に割り当たる）")
        void claim_unassigned_200() throws Exception {
            VillageEntity v = persistVillage();
            persistMembership(v.getId(), VILLAGER_ID, VillageRole.VILLAGER);
            VillageMeetupEntity meetup = persistMeetup(v.getId(), ORGANIZER_ID, VillageMeetupStatus.CONFIRMED);
            VillageMeetupTodoEntity todo = persistTodo(meetup.getId(), null, ORGANIZER_ID);

            authAs(VILLAGER_ID);
            mockMvc.perform(post("/api/v1/villages/{vid}/meetups/{mid}/todos/{tid}/claim",
                            v.getId(), meetup.getId(), todo.getId()))
                    .andExpect(status().isOk());

            assertThat(todoRepository.findById(todo.getId())).get()
                    .satisfies(t -> assertThat(t.getAssigneeUserId()).isEqualTo(VILLAGER_ID));
        }

        @Test
        @DisplayName("既に割当済みの TODO への claim は 409 系 + VILLAGE_095（MEETUP_TODO_ALREADY_CLAIMED）")
        void claim_alreadyAssigned_409() throws Exception {
            VillageEntity v = persistVillage();
            persistMembership(v.getId(), VILLAGER_ID, VillageRole.VILLAGER);
            persistMembership(v.getId(), OTHER_VILLAGER_ID, VillageRole.VILLAGER);
            VillageMeetupEntity meetup = persistMeetup(v.getId(), ORGANIZER_ID, VillageMeetupStatus.CONFIRMED);
            VillageMeetupTodoEntity todo = persistTodo(meetup.getId(), OTHER_VILLAGER_ID, ORGANIZER_ID);

            authAs(VILLAGER_ID);
            mockMvc.perform(post("/api/v1/villages/{vid}/meetups/{mid}/todos/{tid}/claim",
                            v.getId(), meetup.getId(), todo.getId()))
                    .andExpect(status().is4xxClientError())
                    .andExpect(jsonPath("$.error.code").value("VILLAGE_095"));
        }
    }

    @Nested
    @DisplayName("AC-11 宿題 complete（完了）— 手挙げ者本人＋幹事のみ")
    class TodoComplete {

        @Test
        @DisplayName("手挙げ者本人は完了できる（200・done_at セット）")
        void claimant_completes_200() throws Exception {
            VillageEntity v = persistVillage();
            persistMembership(v.getId(), VILLAGER_ID, VillageRole.VILLAGER);
            VillageMeetupEntity meetup = persistMeetup(v.getId(), ORGANIZER_ID, VillageMeetupStatus.CONFIRMED);
            VillageMeetupTodoEntity todo = persistTodo(meetup.getId(), VILLAGER_ID, ORGANIZER_ID);

            authAs(VILLAGER_ID);
            mockMvc.perform(post("/api/v1/villages/{vid}/meetups/{mid}/todos/{tid}/complete",
                            v.getId(), meetup.getId(), todo.getId()))
                    .andExpect(status().isOk());

            assertThat(todoRepository.findById(todo.getId())).get()
                    .satisfies(t -> assertThat(t.getDoneAt()).isNotNull());
        }

        @Test
        @DisplayName("幹事は手挙げ者の TODO を完了できる（200）")
        void organizer_completes_200() throws Exception {
            VillageEntity v = persistVillage();
            persistMembership(v.getId(), VILLAGER_ID, VillageRole.VILLAGER);
            persistMembership(v.getId(), ORGANIZER_ID, VillageRole.VILLAGER);
            VillageMeetupEntity meetup = persistMeetup(v.getId(), ORGANIZER_ID, VillageMeetupStatus.CONFIRMED);
            VillageMeetupTodoEntity todo = persistTodo(meetup.getId(), VILLAGER_ID, ORGANIZER_ID);

            authAs(ORGANIZER_ID);
            mockMvc.perform(post("/api/v1/villages/{vid}/meetups/{mid}/todos/{tid}/complete",
                            v.getId(), meetup.getId(), todo.getId()))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("無関係な村人は完了できない（403）")
        void unrelated_villager_cannot_complete_403() throws Exception {
            VillageEntity v = persistVillage();
            persistMembership(v.getId(), VILLAGER_ID, VillageRole.VILLAGER);
            persistMembership(v.getId(), OTHER_VILLAGER_ID, VillageRole.VILLAGER);
            VillageMeetupEntity meetup = persistMeetup(v.getId(), ORGANIZER_ID, VillageMeetupStatus.CONFIRMED);
            VillageMeetupTodoEntity todo = persistTodo(meetup.getId(), VILLAGER_ID, ORGANIZER_ID);

            authAs(OTHER_VILLAGER_ID);
            mockMvc.perform(post("/api/v1/villages/{vid}/meetups/{mid}/todos/{tid}/complete",
                            v.getId(), meetup.getId(), todo.getId()))
                    .andExpect(status().isForbidden());

            assertThat(todoRepository.findById(todo.getId())).get()
                    .satisfies(t -> assertThat(t.getDoneAt()).isNull());
        }
    }

    @Nested
    @DisplayName("AC-12 宿題 release（手放し）— 本人のみ（幹事でも不可）")
    class TodoRelease {

        @Test
        @DisplayName("割当本人は手放せる（200・assignee が NULL に戻る）")
        void assignee_releases_200() throws Exception {
            VillageEntity v = persistVillage();
            persistMembership(v.getId(), VILLAGER_ID, VillageRole.VILLAGER);
            VillageMeetupEntity meetup = persistMeetup(v.getId(), ORGANIZER_ID, VillageMeetupStatus.CONFIRMED);
            VillageMeetupTodoEntity todo = persistTodo(meetup.getId(), VILLAGER_ID, ORGANIZER_ID);

            authAs(VILLAGER_ID);
            mockMvc.perform(post("/api/v1/villages/{vid}/meetups/{mid}/todos/{tid}/release",
                            v.getId(), meetup.getId(), todo.getId()))
                    .andExpect(status().isOk());

            assertThat(todoRepository.findById(todo.getId())).get()
                    .satisfies(t -> assertThat(t.getAssigneeUserId()).isNull());
        }

        @Test
        @DisplayName("幹事は他人の割当を手放せない（403・権限の非対称・§4.3）")
        void organizer_cannot_release_others_403() throws Exception {
            VillageEntity v = persistVillage();
            persistMembership(v.getId(), VILLAGER_ID, VillageRole.VILLAGER);
            persistMembership(v.getId(), ORGANIZER_ID, VillageRole.VILLAGER);
            VillageMeetupEntity meetup = persistMeetup(v.getId(), ORGANIZER_ID, VillageMeetupStatus.CONFIRMED);
            VillageMeetupTodoEntity todo = persistTodo(meetup.getId(), VILLAGER_ID, ORGANIZER_ID);

            authAs(ORGANIZER_ID);
            mockMvc.perform(post("/api/v1/villages/{vid}/meetups/{mid}/todos/{tid}/release",
                            v.getId(), meetup.getId(), todo.getId()))
                    .andExpect(status().isForbidden());

            assertThat(todoRepository.findById(todo.getId())).get()
                    .satisfies(t -> assertThat(t.getAssigneeUserId()).isEqualTo(VILLAGER_ID));
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // AC-13: decisions_note 更新は幹事＋村長/長老のみ（一般村人は不可）
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("AC-13 決まったこと（decisions_note）更新")
    class DecisionsNote {

        @Test
        @DisplayName("幹事は decisions_note を更新できる（200・永続化される）")
        void organizer_updates_decisions() throws Exception {
            VillageEntity v = persistVillage();
            persistMembership(v.getId(), ORGANIZER_ID, VillageRole.VILLAGER);
            VillageMeetupEntity meetup = persistMeetup(v.getId(), ORGANIZER_ID, VillageMeetupStatus.CONFIRMED);

            authAs(ORGANIZER_ID);
            mockMvc.perform(patch("/api/v1/villages/{vid}/meetups/{mid}", v.getId(), meetup.getId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("decisionsNote", "会費は500円・持ち物は名札"))))
                    .andExpect(status().isOk());

            assertThat(meetupRepository.findById(meetup.getId())).get()
                    .satisfies(m -> assertThat(m.getDecisionsNote()).isEqualTo("会費は500円・持ち物は名札"));
        }

        @Test
        @DisplayName("村長は decisions_note を更新できる（200・幹事でなくても可）")
        void headman_updates_decisions() throws Exception {
            VillageEntity v = persistVillage();
            persistMembership(v.getId(), HEADMAN_ID, VillageRole.HEADMAN);
            VillageMeetupEntity meetup = persistMeetup(v.getId(), ORGANIZER_ID, VillageMeetupStatus.CONFIRMED);

            authAs(HEADMAN_ID);
            mockMvc.perform(patch("/api/v1/villages/{vid}/meetups/{mid}", v.getId(), meetup.getId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("decisionsNote", "村長が議事メモを整えた"))))
                    .andExpect(status().isOk());

            assertThat(meetupRepository.findById(meetup.getId())).get()
                    .satisfies(m -> assertThat(m.getDecisionsNote()).isEqualTo("村長が議事メモを整えた"));
        }

        @Test
        @DisplayName("長老は decisions_note を更新できる（200）")
        void elder_updates_decisions() throws Exception {
            VillageEntity v = persistVillage();
            persistMembership(v.getId(), ELDER_ID, VillageRole.ELDER);
            VillageMeetupEntity meetup = persistMeetup(v.getId(), ORGANIZER_ID, VillageMeetupStatus.CONFIRMED);

            authAs(ELDER_ID);
            mockMvc.perform(patch("/api/v1/villages/{vid}/meetups/{mid}", v.getId(), meetup.getId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("decisionsNote", "長老が補記した"))))
                    .andExpect(status().isOk());

            assertThat(meetupRepository.findById(meetup.getId())).get()
                    .satisfies(m -> assertThat(m.getDecisionsNote()).isEqualTo("長老が補記した"));
        }

        @Test
        @DisplayName("一般村人（幹事でも村長/長老でもない）は decisions_note を更新できない（403）")
        void regular_villager_cannot_update_decisions_403() throws Exception {
            VillageEntity v = persistVillage();
            persistMembership(v.getId(), VILLAGER_ID, VillageRole.VILLAGER);
            VillageMeetupEntity meetup = persistMeetup(v.getId(), ORGANIZER_ID, VillageMeetupStatus.CONFIRMED);

            authAs(VILLAGER_ID);
            mockMvc.perform(patch("/api/v1/villages/{vid}/meetups/{mid}", v.getId(), meetup.getId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("decisionsNote", "一般村人の書き込み"))))
                    .andExpect(status().isForbidden());

            assertThat(meetupRepository.findById(meetup.getId())).get()
                    .satisfies(m -> assertThat(m.getDecisionsNote()).isNull());
        }

        // ── AC-13 組合せ回帰（フィールド単位ガードの抜け穴防止・検分で恒久化） ──────

        @Test
        @DisplayName("非幹事の村長が {title, decisionsNote} 同時送信 → 403 で title も decisions も不適用")
        void headman_combined_title_and_decisions_rejected_403_nothingApplied() throws Exception {
            VillageEntity v = persistVillage();
            persistMembership(v.getId(), HEADMAN_ID, VillageRole.HEADMAN);
            // PLANNING なら core フィールドは本来更新可の状態。だが村長は幹事でないため core ガードで 403。
            VillageMeetupEntity meetup = persistMeetup(v.getId(), ORGANIZER_ID, VillageMeetupStatus.PLANNING);
            String originalTitle = meetupRepository.findById(meetup.getId()).orElseThrow().getTitle();

            authAs(HEADMAN_ID);
            mockMvc.perform(patch("/api/v1/villages/{vid}/meetups/{mid}", v.getId(), meetup.getId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("title", "村長による乗っ取りタイトル",
                                    "decisionsNote", "村長の横取りメモ"))))
                    .andExpect(status().isForbidden());

            // core ガード（幹事限定）で弾かれ、title も decisionsNote も一切書き換わらない。
            assertThat(meetupRepository.findById(meetup.getId())).get()
                    .satisfies(m -> {
                        assertThat(m.getTitle()).isEqualTo(originalTitle);
                        assertThat(m.getDecisionsNote()).isNull();
                    });
        }

        @Test
        @DisplayName("幹事が CONFIRMED で {title, decisionsNote} 同時送信 → MEETUP_INVALID_STATUS で core 拒否・両フィールド不適用")
        void organizer_combined_on_confirmed_rejected_invalidStatus() throws Exception {
            VillageEntity v = persistVillage();
            persistMembership(v.getId(), ORGANIZER_ID, VillageRole.VILLAGER);
            VillageMeetupEntity meetup = persistMeetup(v.getId(), ORGANIZER_ID, VillageMeetupStatus.CONFIRMED);
            String originalTitle = meetupRepository.findById(meetup.getId()).orElseThrow().getTitle();

            authAs(ORGANIZER_ID);
            mockMvc.perform(patch("/api/v1/villages/{vid}/meetups/{mid}", v.getId(), meetup.getId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("title", "確定後のタイトル変更",
                                    "decisionsNote", "同時に決まったことも"))))
                    // core フィールド（title）は PLANNING 限定 → CONFIRMED で MEETUP_INVALID_STATUS（409）
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value("VILLAGE_071"));

            // core 拒否が先に立つため、decisionsNote も title も書き換わらない。
            assertThat(meetupRepository.findById(meetup.getId())).get()
                    .satisfies(m -> {
                        assertThat(m.getTitle()).isEqualTo(originalTitle);
                        assertThat(m.getDecisionsNote()).isNull();
                    });
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // §4.5 CANCELLED 状態ゲート — 中止済み寄合は読み取りのみ（書込み系は MEETUP_INVALID_STATUS）
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("§4.5 CANCELLED 状態ゲート（書込み系は 409 MEETUP_INVALID_STATUS）")
    class CancelledStateGate {

        @Test
        @DisplayName("CANCELLED 寄合へのコメント投稿は 409（VILLAGE_071）")
        void cancelled_createComment_409() throws Exception {
            VillageEntity v = persistVillage();
            persistMembership(v.getId(), VILLAGER_ID, VillageRole.VILLAGER);
            VillageMeetupEntity meetup = persistMeetup(v.getId(), ORGANIZER_ID, VillageMeetupStatus.CANCELLED);

            authAs(VILLAGER_ID);
            mockMvc.perform(post("/api/v1/villages/{vid}/meetups/{mid}/comments", v.getId(), meetup.getId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("body", "中止後の書き込み"))))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value("VILLAGE_071"));
        }

        @Test
        @DisplayName("CANCELLED 寄合への宿題作成は 409（VILLAGE_071）")
        void cancelled_createTodo_409() throws Exception {
            VillageEntity v = persistVillage();
            persistMembership(v.getId(), ORGANIZER_ID, VillageRole.VILLAGER);
            VillageMeetupEntity meetup = persistMeetup(v.getId(), ORGANIZER_ID, VillageMeetupStatus.CANCELLED);

            authAs(ORGANIZER_ID);
            mockMvc.perform(post("/api/v1/villages/{vid}/meetups/{mid}/todos", v.getId(), meetup.getId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("title", "中止後の宿題"))))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value("VILLAGE_071"));
        }

        @Test
        @DisplayName("CANCELLED 寄合の TODO claim は 409（VILLAGE_071）")
        void cancelled_claimTodo_409() throws Exception {
            VillageEntity v = persistVillage();
            persistMembership(v.getId(), VILLAGER_ID, VillageRole.VILLAGER);
            VillageMeetupEntity meetup = persistMeetup(v.getId(), ORGANIZER_ID, VillageMeetupStatus.CANCELLED);
            VillageMeetupTodoEntity todo = persistTodo(meetup.getId(), null, ORGANIZER_ID);

            authAs(VILLAGER_ID);
            mockMvc.perform(post("/api/v1/villages/{vid}/meetups/{mid}/todos/{tid}/claim",
                            v.getId(), meetup.getId(), todo.getId()))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value("VILLAGE_071"));
        }

        @Test
        @DisplayName("CANCELLED 寄合の TODO complete は 409（VILLAGE_071）")
        void cancelled_completeTodo_409() throws Exception {
            VillageEntity v = persistVillage();
            persistMembership(v.getId(), VILLAGER_ID, VillageRole.VILLAGER);
            VillageMeetupEntity meetup = persistMeetup(v.getId(), ORGANIZER_ID, VillageMeetupStatus.CANCELLED);
            VillageMeetupTodoEntity todo = persistTodo(meetup.getId(), VILLAGER_ID, ORGANIZER_ID);

            authAs(VILLAGER_ID);
            mockMvc.perform(post("/api/v1/villages/{vid}/meetups/{mid}/todos/{tid}/complete",
                            v.getId(), meetup.getId(), todo.getId()))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value("VILLAGE_071"));
        }

        @Test
        @DisplayName("CANCELLED 寄合の TODO release は 409（VILLAGE_071）")
        void cancelled_releaseTodo_409() throws Exception {
            VillageEntity v = persistVillage();
            persistMembership(v.getId(), VILLAGER_ID, VillageRole.VILLAGER);
            VillageMeetupEntity meetup = persistMeetup(v.getId(), ORGANIZER_ID, VillageMeetupStatus.CANCELLED);
            VillageMeetupTodoEntity todo = persistTodo(meetup.getId(), VILLAGER_ID, ORGANIZER_ID);

            authAs(VILLAGER_ID);
            mockMvc.perform(post("/api/v1/villages/{vid}/meetups/{mid}/todos/{tid}/release",
                            v.getId(), meetup.getId(), todo.getId()))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value("VILLAGE_071"));
        }

        @Test
        @DisplayName("CANCELLED 寄合の decisions_note 更新は 409（VILLAGE_071・幹事でも書込み不可）")
        void cancelled_decisionsNote_409() throws Exception {
            VillageEntity v = persistVillage();
            persistMembership(v.getId(), ORGANIZER_ID, VillageRole.VILLAGER);
            VillageMeetupEntity meetup = persistMeetup(v.getId(), ORGANIZER_ID, VillageMeetupStatus.CANCELLED);

            authAs(ORGANIZER_ID);
            mockMvc.perform(patch("/api/v1/villages/{vid}/meetups/{mid}", v.getId(), meetup.getId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("decisionsNote", "中止後に決まったこと更新"))))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value("VILLAGE_071"));

            assertThat(meetupRepository.findById(meetup.getId())).get()
                    .satisfies(m -> assertThat(m.getDecisionsNote()).isNull());
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // IDOR（マスター御下命）: 村メンバーシップガード + クロス村
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("IDOR 村メンバーシップガード（非メンバーは 403）")
    class NonMemberGuard {

        @Test
        @DisplayName("非メンバーの出欠送信は 403（VILLAGE_074 MEETUP_NOT_MEMBER）")
        void outsider_attendance_403() throws Exception {
            VillageEntity v = persistVillage();
            VillageMeetupEntity meetup = persistMeetup(v.getId(), ORGANIZER_ID, VillageMeetupStatus.CONFIRMED);

            authAs(OUTSIDER_ID); // membership を作らない
            mockMvc.perform(put("/api/v1/villages/{vid}/meetups/{mid}/attendance", v.getId(), meetup.getId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("status", "GOING"))))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("VILLAGE_074"));
        }

        @Test
        @DisplayName("非メンバーのコメント投稿は 403（VILLAGE_074）")
        void outsider_comment_403() throws Exception {
            VillageEntity v = persistVillage();
            VillageMeetupEntity meetup = persistMeetup(v.getId(), ORGANIZER_ID, VillageMeetupStatus.CONFIRMED);

            authAs(OUTSIDER_ID);
            mockMvc.perform(post("/api/v1/villages/{vid}/meetups/{mid}/comments", v.getId(), meetup.getId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("body", "部外者の書き込み"))))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("VILLAGE_074"));
        }

        @Test
        @DisplayName("非メンバーの TODO claim は 403（VILLAGE_074）")
        void outsider_claim_403() throws Exception {
            VillageEntity v = persistVillage();
            VillageMeetupEntity meetup = persistMeetup(v.getId(), ORGANIZER_ID, VillageMeetupStatus.CONFIRMED);
            VillageMeetupTodoEntity todo = persistTodo(meetup.getId(), null, ORGANIZER_ID);

            authAs(OUTSIDER_ID);
            mockMvc.perform(post("/api/v1/villages/{vid}/meetups/{mid}/todos/{tid}/claim",
                            v.getId(), meetup.getId(), todo.getId()))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("VILLAGE_074"));
        }
    }

    @Nested
    @DisplayName("IDOR クロス村（別村の meetupId を村IDと食い違わせると 404）")
    class CrossVillage {

        @Test
        @DisplayName("村A の URL で村B の meetup を叩くと 404（VILLAGE_069 MEETUP_NOT_FOUND・存在秘匿）")
        void crossVillage_attendance_404() throws Exception {
            VillageEntity villageA = persistVillage();
            VillageEntity villageB = persistVillage();
            persistMembership(villageA.getId(), VILLAGER_ID, VillageRole.VILLAGER);
            // meetup は村B に属する
            VillageMeetupEntity meetupB = persistMeetup(villageB.getId(), ORGANIZER_ID, VillageMeetupStatus.CONFIRMED);

            authAs(VILLAGER_ID);
            mockMvc.perform(put("/api/v1/villages/{vid}/meetups/{mid}/attendance",
                            villageA.getId(), meetupB.getId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("status", "GOING"))))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("VILLAGE_069"));
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // ヘルパー
    // ══════════════════════════════════════════════════════════════════════

    private void authAs(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
    }

    private String json(Object body) throws Exception {
        return objectMapper.writeValueAsString(body);
    }

    private VillageEntity persistVillage() {
        VillageEntity v = VillageEntity.builder()
                .slug("mtg-" + Long.toHexString(System.nanoTime()))
                .name("寄合村" + System.nanoTime())
                .description("寄合後半戦テスト村")
                .type(VillageType.COMMUNITY)
                .joinPolicy(VillageJoinPolicy.FREE)
                .visibility(VillageVisibility.PUBLIC)
                .category("テスト")
                .memberCountCache(0L)
                .createdByUserId(HEADMAN_ID)
                .build();
        return villageRepository.saveAndFlush(v);
    }

    private VillageMembershipEntity persistMembership(UUID villageId, Long userId, VillageRole role) {
        VillageMembershipEntity m = VillageMembershipEntity.builder()
                .villageId(villageId)
                .subjectType(VillageSubjectType.USER)
                .subjectId(userId)
                .role(role)
                .joinedAt(LocalDateTime.now())
                .build();
        return membershipRepository.saveAndFlush(m);
    }

    private VillageMeetupEntity persistMeetup(UUID villageId, Long organizerUserId, VillageMeetupStatus status) {
        VillageMeetupEntity.VillageMeetupEntityBuilder<?, ?> b = VillageMeetupEntity.builder()
                .villageId(villageId)
                .title("寄合" + System.nanoTime())
                .organizerUserId(organizerUserId)
                .status(status);
        if (status == VillageMeetupStatus.CONFIRMED) {
            b.confirmedDate(LocalDate.of(2026, 8, 1));
        }
        return meetupRepository.saveAndFlush(b.build());
    }

    private VillageMeetupCommentEntity persistComment(UUID meetupId, Long authorUserId, String body,
                                                      LocalDateTime createdAt) {
        VillageMeetupCommentEntity c = VillageMeetupCommentEntity.builder()
                .meetupId(meetupId)
                .authorUserId(authorUserId)
                .body(body)
                .createdAt(createdAt)   // @PrePersist は createdAt==null のときのみ上書きするため昇順検証が安定する
                .build();
        return commentRepository.saveAndFlush(c);
    }

    private VillageMeetupTodoEntity persistTodo(UUID meetupId, Long assigneeUserId, Long createdBy) {
        VillageMeetupTodoEntity t = VillageMeetupTodoEntity.builder()
                .meetupId(meetupId)
                .title("宿題" + System.nanoTime())
                .assigneeUserId(assigneeUserId)
                .createdBy(createdBy)
                .build();
        return todoRepository.saveAndFlush(t);
    }
}
