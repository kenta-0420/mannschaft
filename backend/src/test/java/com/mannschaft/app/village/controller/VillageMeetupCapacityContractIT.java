package com.mannschaft.app.village.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.common.storage.R2StorageService;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.village.entity.VillageEntity;
import com.mannschaft.app.village.entity.VillageMeetupAttendanceEntity;
import com.mannschaft.app.village.entity.VillageMeetupEntity;
import com.mannschaft.app.village.entity.VillageMembershipEntity;
import com.mannschaft.app.village.entity.enums.VillageJoinPolicy;
import com.mannschaft.app.village.entity.enums.VillageMeetupAttendanceStatus;
import com.mannschaft.app.village.entity.enums.VillageMeetupStatus;
import com.mannschaft.app.village.entity.enums.VillageRole;
import com.mannschaft.app.village.entity.enums.VillageSubjectType;
import com.mannschaft.app.village.entity.enums.VillageType;
import com.mannschaft.app.village.entity.enums.VillageVisibility;
import com.mannschaft.app.village.repository.VillageMeetupAttendanceRepository;
import com.mannschaft.app.village.repository.VillageMeetupRepository;
import com.mannschaft.app.village.repository.VillageMembershipRepository;
import com.mannschaft.app.village.repository.VillageRepository;
import org.junit.jupiter.api.AfterEach;
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
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F17.2 追補 — 寄合定員（capacity）API 契約テスト（試練 / red 先行・AC-1〜19）。
 *
 * <h2>本テストの性質（重要 / 検分時に必読）</h2>
 *
 * <p><strong>試練（red）テスト</strong>。型スケルトンだけを積んだ現時点では、
 * 定員強制・実 goingCount 結線・下限バリデーション・capacity 編集権者拡張が未実装なので
 * enforcement/読み出し/バリデーション/認可拡張の AC は失敗（red）する。骨格で偶然通る
 * 保存系・境界緑の AC（AC-1/2/3/6/8/11/12/16）は green。出陣（実装）で全 green 化する。</p>
 *
 * <p>金型: {@link VillageMeetupSecondHalfContractIT}
 * （{@code AbstractMySqlIntegrationTest} + {@code @AutoConfigureMockMvc(addFilters=false)} + {@code @Transactional}）。
 * フィクスチャ流儀（村・membership・meetup を Repository で作る／日時は {@code LocalDateTime} bind）も踏襲する。</p>
 *
 * <h2>確定仕様（マスター御裁可済）</h2>
 * <ul>
 *   <li>capacity は GOING 出欠のみ制約（MAYBE/ABSENT は無制約）。null=無制限。</li>
 *   <li>満席で新規 GOING は 409 / VILLAGE_103（MEETUP_CAPACITY_FULL）。</li>
 *   <li>既に GOING の本人の再 GOING は満席でも冪等成功。GOING→MAYBE/ABSENT で枠が空く。</li>
 *   <li>capacity>=1 または null のみ許可。0/負値は 400。</li>
 *   <li>capacity 編集権者 = 幹事＋村長/長老。編集は PLANNING/CONFIRMED どちらでも可。</li>
 *   <li>現 GOING より小さい定員へ更新可（既存 GOING 保持・remaining=0・以後の新規 GOING は塞ぐ）。</li>
 * </ul>
 *
 * <p>AC-20（並行）は別クラス {@code VillageMeetupCapacityConcurrencyIT}（@Transactional なしで
 * 実コミット並行を検証）に分離した。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("F17.2 追補 寄合定員 API 契約テスト（試練・red・AC-1〜19）")
class VillageMeetupCapacityContractIT extends AbstractMySqlIntegrationTest {

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
    private VillageMeetupAttendanceRepository attendanceRepository;

    /** 署名 URL 計算は外部境界。姉妹契約テストとコンテキスト構成を揃えるため mock 宣言。 */
    @MockitoBean
    private R2StorageService r2StorageService;

    private static final Long ORGANIZER_ID = 17_203_001L; // 幹事（作成者）
    private static final Long HEADMAN_ID = 17_203_002L;    // 村長
    private static final Long ELDER_ID = 17_203_003L;      // 長老
    private static final Long VILLAGER_ID = 17_203_004L;   // 一般村人
    private static final Long U1 = 17_203_011L;            // GOING 用村人
    private static final Long U2 = 17_203_012L;
    private static final Long U3 = 17_203_013L;
    private static final Long U4 = 17_203_014L;
    private static final Long OUTSIDER_ID = 17_203_099L; // 当該村に所属しない部外者

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ══════════════════════════════════════════════════════════════════════
    // capacity 保存・更新（骨格で green 想定：AC-1/2/3）
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("capacity 保存・更新（AC-1/2/3）")
    class SaveAndUpdate {

        @Test
        @DisplayName("AC-1 幹事が capacity=5 で作成 → 201 かつ DB に capacity=5 が保存される")
        void create_withCapacity_persisted_AC1() throws Exception {
            VillageEntity v = persistVillage();
            persistMembership(v.getId(), ORGANIZER_ID, VillageRole.VILLAGER);

            authAs(ORGANIZER_ID);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("title", "定員つき新年会");
            body.put("candidateDates", List.of(Map.of("date", "2026-08-01")));
            body.put("capacity", 5);
            mockMvc.perform(post("/api/v1/villages/{vid}/meetups", v.getId())
                            .contentType(MediaType.APPLICATION_JSON).content(json(body)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.capacity").value(5));

            assertThat(meetupRepository.findByVillageIdAndDeletedAtIsNull(
                            v.getId(), org.springframework.data.domain.PageRequest.of(0, 10)).getContent())
                    .anySatisfy(m -> assertThat(m.getCapacity()).isEqualTo(5));
        }

        @Test
        @DisplayName("AC-2 capacity 省略（null=無制限）で作成 → 201 かつ DB capacity=null")
        void create_withoutCapacity_null_AC2() throws Exception {
            VillageEntity v = persistVillage();
            persistMembership(v.getId(), ORGANIZER_ID, VillageRole.VILLAGER);

            authAs(ORGANIZER_ID);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("title", "定員なし寄合");
            body.put("candidateDates", List.of(Map.of("date", "2026-08-01")));
            // capacity は入れない（=null）
            mockMvc.perform(post("/api/v1/villages/{vid}/meetups", v.getId())
                            .contentType(MediaType.APPLICATION_JSON).content(json(body)))
                    .andExpect(status().isCreated());

            assertThat(meetupRepository.findByVillageIdAndDeletedAtIsNull(
                            v.getId(), org.springframework.data.domain.PageRequest.of(0, 10)).getContent())
                    .anySatisfy(m -> assertThat(m.getCapacity()).isNull());
        }

        @Test
        @DisplayName("AC-3 幹事が PLANNING で capacity を 5→10 に更新 → 200 かつ DB=10")
        void update_capacity_byOrganizer_planning_AC3() throws Exception {
            VillageEntity v = persistVillage();
            persistMembership(v.getId(), ORGANIZER_ID, VillageRole.VILLAGER);
            VillageMeetupEntity meetup = persistMeetup(v.getId(), ORGANIZER_ID, VillageMeetupStatus.PLANNING, 5);

            authAs(ORGANIZER_ID);
            mockMvc.perform(patch("/api/v1/villages/{vid}/meetups/{mid}", v.getId(), meetup.getId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("capacity", 10))))
                    .andExpect(status().isOk());

            assertThat(meetupRepository.findById(meetup.getId())).get()
                    .satisfies(m -> assertThat(m.getCapacity()).isEqualTo(10));
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // 読み出し（RED：goingCount 実カウント未結線）AC-4/5
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("読み出し goingCount / remainingSlots（AC-4/5・RED）")
    class ReadOut {

        @Test
        @DisplayName("AC-4 詳細取得の goingCount が実 GOING 数（2）で載る")
        void detail_goingCount_reflectsReal_AC4() throws Exception {
            VillageEntity v = persistVillage();
            persistMembership(v.getId(), U1, VillageRole.VILLAGER);
            persistMembership(v.getId(), U2, VillageRole.VILLAGER);
            VillageMeetupEntity meetup = persistMeetup(v.getId(), ORGANIZER_ID, VillageMeetupStatus.CONFIRMED, 5);
            persistAttendance(meetup.getId(), U1, VillageMeetupAttendanceStatus.GOING);
            persistAttendance(meetup.getId(), U2, VillageMeetupAttendanceStatus.GOING);

            authAs(U1);
            mockMvc.perform(get("/api/v1/villages/{vid}/meetups/{mid}", v.getId(), meetup.getId()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.goingCount").value(2));
        }

        @Test
        @DisplayName("AC-5 remainingSlots = capacity - goingCount（5-2=3）で載る")
        void detail_remainingSlots_computed_AC5() throws Exception {
            VillageEntity v = persistVillage();
            persistMembership(v.getId(), U1, VillageRole.VILLAGER);
            persistMembership(v.getId(), U2, VillageRole.VILLAGER);
            VillageMeetupEntity meetup = persistMeetup(v.getId(), ORGANIZER_ID, VillageMeetupStatus.CONFIRMED, 5);
            persistAttendance(meetup.getId(), U1, VillageMeetupAttendanceStatus.GOING);
            persistAttendance(meetup.getId(), U2, VillageMeetupAttendanceStatus.GOING);

            authAs(U1);
            mockMvc.perform(get("/api/v1/villages/{vid}/meetups/{mid}", v.getId(), meetup.getId()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.remainingSlots").value(3));
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // 定員強制（RED の 409 群 / 一部 green）AC-6〜13
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("定員強制 GOING（AC-6〜13）")
    class Enforcement {

        @Test
        @DisplayName("AC-6 capacity=2 の 1人目・2人目 GOING は両方 200（定員ちょうどまで通る）")
        void capacity2_firstTwoGoing_ok_AC6() throws Exception {
            VillageEntity v = persistVillage();
            persistMembership(v.getId(), U1, VillageRole.VILLAGER);
            persistMembership(v.getId(), U2, VillageRole.VILLAGER);
            VillageMeetupEntity meetup = persistMeetup(v.getId(), ORGANIZER_ID, VillageMeetupStatus.CONFIRMED, 2);

            authAs(U1);
            putAttendance(v.getId(), meetup.getId(), "GOING").andExpect(status().isOk());
            authAs(U2);
            putAttendance(v.getId(), meetup.getId(), "GOING").andExpect(status().isOk());
        }

        @Test
        @DisplayName("AC-7 capacity=2 満席で 3人目の新規 GOING は 409 + VILLAGE_103")
        void full_thirdGoing_rejected_409_AC7() throws Exception {
            VillageEntity v = persistVillage();
            persistMembership(v.getId(), U1, VillageRole.VILLAGER);
            persistMembership(v.getId(), U2, VillageRole.VILLAGER);
            persistMembership(v.getId(), U3, VillageRole.VILLAGER);
            VillageMeetupEntity meetup = persistMeetup(v.getId(), ORGANIZER_ID, VillageMeetupStatus.CONFIRMED, 2);
            persistAttendance(meetup.getId(), U1, VillageMeetupAttendanceStatus.GOING);
            persistAttendance(meetup.getId(), U2, VillageMeetupAttendanceStatus.GOING);

            authAs(U3);
            putAttendance(v.getId(), meetup.getId(), "GOING")
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value("VILLAGE_103"));

            // 満席拒否後も GOING 行数は 2 のまま（3 行目は永続化されない）
            assertThat(attendanceRepository.countByMeetupIdAndStatus(
                    meetup.getId(), VillageMeetupAttendanceStatus.GOING)).isEqualTo(2);
        }

        @Test
        @DisplayName("AC-8 満席でも既存 GOING 本人の再送 GOING は 200 冪等成功（本人は塞がれない）")
        void full_existingGoingReSend_idempotent_ok_AC8() throws Exception {
            VillageEntity v = persistVillage();
            persistMembership(v.getId(), U1, VillageRole.VILLAGER);
            VillageMeetupEntity meetup = persistMeetup(v.getId(), ORGANIZER_ID, VillageMeetupStatus.CONFIRMED, 1);
            persistAttendance(meetup.getId(), U1, VillageMeetupAttendanceStatus.GOING);

            authAs(U1);
            putAttendance(v.getId(), meetup.getId(), "GOING").andExpect(status().isOk());

            assertThat(attendanceRepository.countByMeetupIdAndStatus(
                    meetup.getId(), VillageMeetupAttendanceStatus.GOING)).isEqualTo(1);
        }

        @Test
        @DisplayName("AC-9 満席→本人 GOING を ABSENT に変更で枠が空く（解放前は別人 GOING が 409）")
        void goingToAbsent_freesSlot_AC9() throws Exception {
            VillageEntity v = persistVillage();
            persistMembership(v.getId(), U1, VillageRole.VILLAGER);
            persistMembership(v.getId(), U2, VillageRole.VILLAGER);
            VillageMeetupEntity meetup = persistMeetup(v.getId(), ORGANIZER_ID, VillageMeetupStatus.CONFIRMED, 1);
            persistAttendance(meetup.getId(), U1, VillageMeetupAttendanceStatus.GOING);

            // 解放前: 満席なので別人 U2 の新規 GOING は 409（← ここが現状 red）
            authAs(U2);
            putAttendance(v.getId(), meetup.getId(), "GOING")
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value("VILLAGE_103"));

            // U1 が ABSENT に変更 → 枠が空く
            authAs(U1);
            putAttendance(v.getId(), meetup.getId(), "ABSENT").andExpect(status().isOk());

            // 空いた枠に U2 の GOING が通る
            authAs(U2);
            putAttendance(v.getId(), meetup.getId(), "GOING").andExpect(status().isOk());
        }

        @Test
        @DisplayName("AC-10 満席→本人 GOING を MAYBE に変更で枠が空く（MAYBE は席を占有しない）")
        void goingToMaybe_freesSlot_AC10() throws Exception {
            VillageEntity v = persistVillage();
            persistMembership(v.getId(), U1, VillageRole.VILLAGER);
            persistMembership(v.getId(), U2, VillageRole.VILLAGER);
            VillageMeetupEntity meetup = persistMeetup(v.getId(), ORGANIZER_ID, VillageMeetupStatus.CONFIRMED, 1);
            persistAttendance(meetup.getId(), U1, VillageMeetupAttendanceStatus.GOING);

            authAs(U2);
            putAttendance(v.getId(), meetup.getId(), "GOING")
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value("VILLAGE_103"));

            authAs(U1);
            putAttendance(v.getId(), meetup.getId(), "MAYBE").andExpect(status().isOk());

            authAs(U2);
            putAttendance(v.getId(), meetup.getId(), "GOING").andExpect(status().isOk());
        }

        @Test
        @DisplayName("AC-11 MAYBE/ABSENT は満席でも常に受理（capacity は GOING のみ制約）")
        void maybeAbsent_unconstrained_evenWhenFull_AC11() throws Exception {
            VillageEntity v = persistVillage();
            persistMembership(v.getId(), U1, VillageRole.VILLAGER);
            persistMembership(v.getId(), U2, VillageRole.VILLAGER);
            persistMembership(v.getId(), U3, VillageRole.VILLAGER);
            VillageMeetupEntity meetup = persistMeetup(v.getId(), ORGANIZER_ID, VillageMeetupStatus.CONFIRMED, 1);
            persistAttendance(meetup.getId(), U1, VillageMeetupAttendanceStatus.GOING); // 満席

            authAs(U2);
            putAttendance(v.getId(), meetup.getId(), "MAYBE").andExpect(status().isOk());
            authAs(U3);
            putAttendance(v.getId(), meetup.getId(), "ABSENT").andExpect(status().isOk());
        }

        @Test
        @DisplayName("AC-12 capacity=null（無制限）なら GOING を上限なく受理")
        void unlimited_capacityNull_manyGoing_ok_AC12() throws Exception {
            VillageEntity v = persistVillage();
            persistMembership(v.getId(), U1, VillageRole.VILLAGER);
            persistMembership(v.getId(), U2, VillageRole.VILLAGER);
            persistMembership(v.getId(), U3, VillageRole.VILLAGER);
            VillageMeetupEntity meetup = persistMeetup(v.getId(), ORGANIZER_ID, VillageMeetupStatus.CONFIRMED, null);

            authAs(U1);
            putAttendance(v.getId(), meetup.getId(), "GOING").andExpect(status().isOk());
            authAs(U2);
            putAttendance(v.getId(), meetup.getId(), "GOING").andExpect(status().isOk());
            authAs(U3);
            putAttendance(v.getId(), meetup.getId(), "GOING").andExpect(status().isOk());
        }

        @Test
        @DisplayName("AC-13 現 GOING(3) より小さい定員(2) — remaining=0・以後の新規 GOING は 409（既存は保持）")
        void shrinkBelowCurrent_keepsExisting_blocksNew_AC13() throws Exception {
            VillageEntity v = persistVillage();
            persistMembership(v.getId(), U1, VillageRole.VILLAGER);
            persistMembership(v.getId(), U2, VillageRole.VILLAGER);
            persistMembership(v.getId(), U3, VillageRole.VILLAGER);
            persistMembership(v.getId(), U4, VillageRole.VILLAGER);
            // capacity=2 に対して既に GOING が 3 人いる状態（縮小後を模す）
            VillageMeetupEntity meetup = persistMeetup(v.getId(), ORGANIZER_ID, VillageMeetupStatus.CONFIRMED, 2);
            persistAttendance(meetup.getId(), U1, VillageMeetupAttendanceStatus.GOING);
            persistAttendance(meetup.getId(), U2, VillageMeetupAttendanceStatus.GOING);
            persistAttendance(meetup.getId(), U3, VillageMeetupAttendanceStatus.GOING);

            // remaining は負値にせず 0 に丸める（既存 GOING は保持）
            authAs(U1);
            mockMvc.perform(get("/api/v1/villages/{vid}/meetups/{mid}", v.getId(), meetup.getId()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.remainingSlots").value(0));

            // 以後の新規 GOING は塞ぐ
            authAs(U4);
            putAttendance(v.getId(), meetup.getId(), "GOING")
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value("VILLAGE_103"));

            // 既存 GOING 3 人は保持（キックしない）
            assertThat(attendanceRepository.countByMeetupIdAndStatus(
                    meetup.getId(), VillageMeetupAttendanceStatus.GOING)).isEqualTo(3);
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // バリデーション（RED：@Min 未付与）AC-14/15
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("capacity バリデーション（AC-14/15・RED）")
    class Validation {

        @Test
        @DisplayName("AC-14 capacity=0 での作成は 400 で拒否")
        void create_capacityZero_rejected_400_AC14() throws Exception {
            VillageEntity v = persistVillage();
            persistMembership(v.getId(), ORGANIZER_ID, VillageRole.VILLAGER);

            authAs(ORGANIZER_ID);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("title", "定員ゼロ");
            body.put("candidateDates", List.of(Map.of("date", "2026-08-01")));
            body.put("capacity", 0);
            mockMvc.perform(post("/api/v1/villages/{vid}/meetups", v.getId())
                            .contentType(MediaType.APPLICATION_JSON).content(json(body)))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("AC-15 capacity=-1 での作成は 400 で拒否")
        void create_capacityNegative_rejected_400_AC15() throws Exception {
            VillageEntity v = persistVillage();
            persistMembership(v.getId(), ORGANIZER_ID, VillageRole.VILLAGER);

            authAs(ORGANIZER_ID);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("title", "定員マイナス");
            body.put("candidateDates", List.of(Map.of("date", "2026-08-01")));
            body.put("capacity", -1);
            mockMvc.perform(post("/api/v1/villages/{vid}/meetups", v.getId())
                            .contentType(MediaType.APPLICATION_JSON).content(json(body)))
                    .andExpect(status().isBadRequest());
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // 認可（capacity 編集権者 = 幹事＋村長/長老・PLANNING/CONFIRMED 両可）AC-16/17/18
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("capacity 編集認可（AC-16/17/18）")
    class EditAuthz {

        @Test
        @DisplayName("AC-16 一般村人（非幹事・非村長/長老）は capacity を編集できない（403）")
        void regularVillager_cannotEditCapacity_403_AC16() throws Exception {
            VillageEntity v = persistVillage();
            persistMembership(v.getId(), VILLAGER_ID, VillageRole.VILLAGER);
            VillageMeetupEntity meetup = persistMeetup(v.getId(), ORGANIZER_ID, VillageMeetupStatus.PLANNING, 5);

            authAs(VILLAGER_ID);
            mockMvc.perform(patch("/api/v1/villages/{vid}/meetups/{mid}", v.getId(), meetup.getId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("capacity", 3))))
                    .andExpect(status().isForbidden());

            assertThat(meetupRepository.findById(meetup.getId())).get()
                    .satisfies(m -> assertThat(m.getCapacity()).isEqualTo(5));
        }

        @Test
        @DisplayName("AC-17 村長（非幹事）は capacity を編集できる（200・幹事と同格）")
        void headman_canEditCapacity_200_AC17() throws Exception {
            VillageEntity v = persistVillage();
            persistMembership(v.getId(), HEADMAN_ID, VillageRole.HEADMAN);
            VillageMeetupEntity meetup = persistMeetup(v.getId(), ORGANIZER_ID, VillageMeetupStatus.PLANNING, 5);

            authAs(HEADMAN_ID);
            mockMvc.perform(patch("/api/v1/villages/{vid}/meetups/{mid}", v.getId(), meetup.getId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("capacity", 8))))
                    .andExpect(status().isOk());

            assertThat(meetupRepository.findById(meetup.getId())).get()
                    .satisfies(m -> assertThat(m.getCapacity()).isEqualTo(8));
        }

        @Test
        @DisplayName("AC-18 幹事は CONFIRMED でも capacity を編集できる（200・PLANNING 限定ではない）")
        void organizer_canEditCapacity_onConfirmed_200_AC18() throws Exception {
            VillageEntity v = persistVillage();
            persistMembership(v.getId(), ORGANIZER_ID, VillageRole.VILLAGER);
            VillageMeetupEntity meetup = persistMeetup(v.getId(), ORGANIZER_ID, VillageMeetupStatus.CONFIRMED, 5);

            authAs(ORGANIZER_ID);
            mockMvc.perform(patch("/api/v1/villages/{vid}/meetups/{mid}", v.getId(), meetup.getId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("capacity", 12))))
                    .andExpect(status().isOk());

            assertThat(meetupRepository.findById(meetup.getId())).get()
                    .satisfies(m -> assertThat(m.getCapacity()).isEqualTo(12));
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // 一覧の goingCount バッチ供給（N+1 回避）AC-19
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("一覧 goingCount バッチ供給（AC-19・RED）")
    class ListBatch {

        @Test
        @DisplayName("AC-19 一覧レスポンスの各寄合に goingCount がバッチで載る（実 GOING 数）")
        void list_returnsGoingCount_batched_AC19() throws Exception {
            VillageEntity v = persistVillage();
            persistMembership(v.getId(), U1, VillageRole.VILLAGER);
            persistMembership(v.getId(), U2, VillageRole.VILLAGER);
            VillageMeetupEntity mA = persistMeetup(v.getId(), ORGANIZER_ID, VillageMeetupStatus.CONFIRMED, 5);
            VillageMeetupEntity mB = persistMeetup(v.getId(), ORGANIZER_ID, VillageMeetupStatus.CONFIRMED, 5);
            // mA: GOING 2 / mB: GOING 1
            persistAttendance(mA.getId(), U1, VillageMeetupAttendanceStatus.GOING);
            persistAttendance(mA.getId(), U2, VillageMeetupAttendanceStatus.GOING);
            persistAttendance(mB.getId(), U1, VillageMeetupAttendanceStatus.GOING);

            authAs(U1);
            // 順序非依存で「どれかに goingCount=2 が載る」ことを検証（N+1 回避のバッチ供給は出陣で担保）。
            mockMvc.perform(get("/api/v1/villages/{vid}/meetups", v.getId()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[*].goingCount", hasItem(2)));
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // 殿の台帳 AC 補完（IDOR / BOLA / 境界）— AC-16(IDOR)/AC-18(BOLA)/AC-12(境界)/AC-13(残枠)
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("台帳AC補完 IDOR/BOLA/境界（殿 AC-16/18/12/13）")
    class LedgerGapCoverage {

        @Test
        @DisplayName("[殿AC-16 IDOR] 非村メンバーの capacity 操作(作成/更新/GOING)は 403（requireVillager/認可で弾く）")
        void nonMember_capacityOps_forbidden_AC16() throws Exception {
            VillageEntity v = persistVillage();
            // OUTSIDER は membership を作らない（村に所属しない）
            VillageMeetupEntity planning = persistMeetup(v.getId(), ORGANIZER_ID, VillageMeetupStatus.PLANNING, 5);
            VillageMeetupEntity confirmed = persistMeetup(v.getId(), ORGANIZER_ID, VillageMeetupStatus.CONFIRMED, 5);

            authAs(OUTSIDER_ID);
            // (a) capacity 付き作成 → 403（村人でない）
            Map<String, Object> createBody = new LinkedHashMap<>();
            createBody.put("title", "部外者の定員つき作成");
            createBody.put("candidateDates", List.of(Map.of("date", "2026-08-01")));
            createBody.put("capacity", 3);
            mockMvc.perform(post("/api/v1/villages/{vid}/meetups", v.getId())
                            .contentType(MediaType.APPLICATION_JSON).content(json(createBody)))
                    .andExpect(status().isForbidden());

            // (b) capacity 更新 → 403（幹事でも村長/長老でもない）
            mockMvc.perform(patch("/api/v1/villages/{vid}/meetups/{mid}", v.getId(), planning.getId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("capacity", 9))))
                    .andExpect(status().isForbidden());

            // (c) 出欠 GOING → 403（村人でない）
            putAttendance(v.getId(), confirmed.getId(), "GOING")
                    .andExpect(status().isForbidden());

            // capacity は書き換わっていない
            assertThat(meetupRepository.findById(planning.getId())).get()
                    .satisfies(m -> assertThat(m.getCapacity()).isEqualTo(5));
        }

        @Test
        @DisplayName("[殿AC-18 BOLA] 村Aの URL で村Bの meetupId を叩く get/update/attendance は 404（VILLAGE_069・存在秘匿）")
        void crossVillage_meetupId_notFound_AC18() throws Exception {
            VillageEntity villageA = persistVillage();
            VillageEntity villageB = persistVillage();
            persistMembership(villageA.getId(), VILLAGER_ID, VillageRole.VILLAGER); // A の村人
            VillageMeetupEntity meetupB =
                    persistMeetup(villageB.getId(), ORGANIZER_ID, VillageMeetupStatus.CONFIRMED, 5);

            authAs(VILLAGER_ID);
            // get
            mockMvc.perform(get("/api/v1/villages/{vid}/meetups/{mid}", villageA.getId(), meetupB.getId()))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("VILLAGE_069"));
            // update（capacity）
            mockMvc.perform(patch("/api/v1/villages/{vid}/meetups/{mid}", villageA.getId(), meetupB.getId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("capacity", 7))))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("VILLAGE_069"));
            // attendance
            mockMvc.perform(put("/api/v1/villages/{vid}/meetups/{mid}/attendance", villageA.getId(), meetupB.getId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("status", "GOING"))))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("VILLAGE_069"));
        }

        @Test
        @DisplayName("[殿AC-12 境界] capacity=1・GOING0 → 1人目 GOING 200・2人目 GOING 409(VILLAGE_103)")
        void capacity1_boundary_secondGoing_409_AC12() throws Exception {
            VillageEntity v = persistVillage();
            persistMembership(v.getId(), U1, VillageRole.VILLAGER);
            persistMembership(v.getId(), U2, VillageRole.VILLAGER);
            VillageMeetupEntity meetup = persistMeetup(v.getId(), ORGANIZER_ID, VillageMeetupStatus.CONFIRMED, 1);

            authAs(U1);
            putAttendance(v.getId(), meetup.getId(), "GOING").andExpect(status().isOk());
            authAs(U2);
            putAttendance(v.getId(), meetup.getId(), "GOING")
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value("VILLAGE_103"));

            assertThat(attendanceRepository.countByMeetupIdAndStatus(
                    meetup.getId(), VillageMeetupAttendanceStatus.GOING)).isEqualTo(1);
        }

        @Test
        @DisplayName("[殿AC-13 残枠] GOING0 の寄合詳細は remainingSlots が capacity(非null) と一致し goingCount=0")
        void zeroGoing_remainingEqualsCapacity_AC13() throws Exception {
            VillageEntity v = persistVillage();
            persistMembership(v.getId(), VILLAGER_ID, VillageRole.VILLAGER);
            VillageMeetupEntity meetup = persistMeetup(v.getId(), ORGANIZER_ID, VillageMeetupStatus.CONFIRMED, 5);

            authAs(VILLAGER_ID);
            mockMvc.perform(get("/api/v1/villages/{vid}/meetups/{mid}", v.getId(), meetup.getId()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.capacity").value(5))
                    .andExpect(jsonPath("$.data.goingCount").value(0))
                    .andExpect(jsonPath("$.data.remainingSlots").value(5));
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // ヘルパー
    // ══════════════════════════════════════════════════════════════════════

    private org.springframework.test.web.servlet.ResultActions putAttendance(UUID villageId, UUID meetupId,
                                                                              String statusValue) throws Exception {
        return mockMvc.perform(put("/api/v1/villages/{vid}/meetups/{mid}/attendance", villageId, meetupId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("status", statusValue))));
    }

    private void authAs(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
    }

    private String json(Object body) throws Exception {
        return objectMapper.writeValueAsString(body);
    }

    private VillageEntity persistVillage() {
        VillageEntity v = VillageEntity.builder()
                .slug("cap-" + Long.toHexString(System.nanoTime()))
                .name("定員村" + System.nanoTime())
                .description("寄合定員テスト村")
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

    private VillageMeetupEntity persistMeetup(UUID villageId, Long organizerUserId,
                                              VillageMeetupStatus status, Integer capacity) {
        VillageMeetupEntity.VillageMeetupEntityBuilder<?, ?> b = VillageMeetupEntity.builder()
                .villageId(villageId)
                .title("寄合" + System.nanoTime())
                .organizerUserId(organizerUserId)
                .status(status)
                .capacity(capacity);
        if (status == VillageMeetupStatus.CONFIRMED) {
            b.confirmedDate(LocalDate.of(2026, 8, 1));
        }
        return meetupRepository.saveAndFlush(b.build());
    }

    private VillageMeetupAttendanceEntity persistAttendance(UUID meetupId, Long userId,
                                                            VillageMeetupAttendanceStatus statusValue) {
        VillageMeetupAttendanceEntity a = VillageMeetupAttendanceEntity.builder()
                .meetupId(meetupId)
                .userId(userId)
                .status(statusValue)
                .build();
        return attendanceRepository.saveAndFlush(a);
    }
}
