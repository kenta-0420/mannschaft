package com.mannschaft.app.village.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.common.storage.R2StorageService;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.village.entity.VillageCalendarEventEntity;
import com.mannschaft.app.village.entity.VillageCalendarEventLogEntity;
import com.mannschaft.app.village.entity.VillageEntity;
import com.mannschaft.app.village.entity.VillageMembershipEntity;
import com.mannschaft.app.village.entity.enums.VillageJoinPolicy;
import com.mannschaft.app.village.entity.enums.VillageRole;
import com.mannschaft.app.village.entity.enums.VillageSubjectType;
import com.mannschaft.app.village.entity.enums.VillageType;
import com.mannschaft.app.village.entity.enums.VillageVisibility;
import com.mannschaft.app.village.repository.VillageCalendarEventLogRepository;
import com.mannschaft.app.village.repository.VillageCalendarEventRepository;
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
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F17.2 Wave1 ④歳時記×村史の年輪 — API 契約テスト（試練 / red 先行）。
 *
 * <h2>本テストの性質（重要 / 検分時に必読）</h2>
 *
 * <p><strong>red テスト</strong>。骨格のみ（Service/Controller 未実装）の現時点では
 * すべて失敗する。未実装 EP は {@code NoResourceFoundException} で 404 + {@code COMMON_005} を
 * 返すため、目標契約（201/204/年降順/署名URL/VILLAGE_101 等）を突きつける本テストは必ず赤い。</p>
 *
 * <p>方式・金型は姉妹クラス
 * {@link VillageMeetupSecondHalfContractIT}（{@code AbstractMySqlIntegrationTest} +
 * {@code @AutoConfigureMockMvc(addFilters=false)}）と同一構成。コンテキスト構成を揃えて
 * TestContext キャッシュを共有する（{@code @MockitoBean R2StorageService} も同一宣言）。</p>
 *
 * <p>受け入れ条件（設計書 §11.4）: AC-18・AC-19・AC-18b。加えてマスター御下命の
 * IDOR（村メンバーシップガード・クロス村 404）を明示検証する。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("F17.2 Wave1 ④年輪 API 契約テスト（試練・red）")
class VillageCalendarLogRingContractIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private VillageRepository villageRepository;

    @Autowired
    private VillageMembershipRepository membershipRepository;

    @Autowired
    private VillageCalendarEventRepository calendarEventRepository;

    @Autowired
    private VillageCalendarEventLogRepository logRepository;

    /** 署名 URL 計算は外部境界。決定論のため mock 化（AC-19 の署名URL解決検証に使用）。 */
    @MockitoBean
    private R2StorageService r2StorageService;

    private static final Long HEADMAN_ID = 17_240_001L;    // 村長
    private static final Long ELDER_ID = 17_240_002L;       // 長老
    private static final Long VILLAGER_ID = 17_240_003L;    // 記録者（一般村人）
    private static final Long OTHER_VILLAGER_ID = 17_240_004L; // 無関係な村人
    private static final Long OUTSIDER_ID = 17_240_005L;    // 村メンバーでない部外者

    private static final String SIGNED_URL_PREFIX = "https://r2.example.com/";

    @BeforeEach
    void setUp() {
        // 生キーを受け取り決定論的な絶対 URL を返す（実 R2 と同じ形）。AC-19 の署名URL解決で使う。
        lenient().when(r2StorageService.generateDownloadUrl(anyString(), any(Duration.class)))
                .thenAnswer(inv -> SIGNED_URL_PREFIX + inv.getArgument(0) + "?sig=it");
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ══════════════════════════════════════════════════════════════════════
    // AC-18: 同一 year に複数件 POST できる（両方 201・一覧に2件）
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("AC-18 年輪の追加（POST .../logs・同一 year 複数件）")
    class AddLog {

        @Test
        @DisplayName("同一 calendar_event×同一 year へ複数件 POST でき、いずれも 201・一覧に2件残る")
        void sameYear_multipleLogs_bothCreated() throws Exception {
            VillageEntity v = persistVillage();
            persistMembership(v.getId(), VILLAGER_ID, VillageRole.VILLAGER);
            VillageCalendarEventEntity event = persistCalendarEvent(v.getId());

            authAs(VILLAGER_ID);
            mockMvc.perform(post("/api/v1/villages/{vid}/calendar-events/{eid}/logs", v.getId(), event.getId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(logBody(2026, null, "一枚目の様子"))))
                    .andExpect(status().isCreated());

            mockMvc.perform(post("/api/v1/villages/{vid}/calendar-events/{eid}/logs", v.getId(), event.getId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(logBody(2026, null, "二枚目の様子"))))
                    .andExpect(status().isCreated());

            // (calendar_event_id, year) に UNIQUE を張らない設計（§6.3）→ 2 件残る
            assertThat(logRepository
                    .findByCalendarEventIdAndYearAndDeletedAtIsNullOrderByCreatedAtDesc(
                            event.getId(), 2026, PageRequest.of(0, 20))
                    .getTotalElements())
                    .isEqualTo(2);
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // AC-19: 一覧は year 降順・photo_r2_key ありの行は署名URLを返す
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("AC-19 年輪一覧（GET .../logs・year 降順・署名URL解決）")
    class ListLogs {

        @Test
        @DisplayName("年輪一覧は year 降順で返り、photo_r2_key は署名 URL に解決される")
        void list_yearDescending_withSignedUrl() throws Exception {
            VillageEntity v = persistVillage();
            persistMembership(v.getId(), VILLAGER_ID, VillageRole.VILLAGER);
            VillageCalendarEventEntity event = persistCalendarEvent(v.getId());
            // 古い年→新しい年の順で保存しても、一覧は year 降順（新しい年が先頭）で返るべき
            persistLog(event.getId(), 2025, "village/calendar-log/y2025.png", "一昨年", VILLAGER_ID,
                    LocalDateTime.of(2025, 8, 1, 9, 0));
            persistLog(event.getId(), 2026, "village/calendar-log/y2026.png", "去年", VILLAGER_ID,
                    LocalDateTime.of(2026, 8, 1, 9, 0));

            authAs(VILLAGER_ID);
            mockMvc.perform(get("/api/v1/villages/{vid}/calendar-events/{eid}/logs", v.getId(), event.getId()))
                    .andExpect(status().isOk())
                    // year 降順（2026 が先頭）
                    .andExpect(jsonPath("$.data[0].year").value(2026))
                    .andExpect(jsonPath("$.data[1].year").value(2025))
                    // photo_r2_key は生キーではなく署名 URL に解決される（§6.3・resolveAll）
                    .andExpect(jsonPath("$.data[0].photoUrl").value(org.hamcrest.Matchers.startsWith(SIGNED_URL_PREFIX)));
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // AC-18b: 年輪削除は投稿者本人＋村長/長老のみ・無関係村人は VILLAGE_101
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("AC-18b 年輪の削除（DELETE .../logs/{logId}）")
    class DeleteLog {

        @Test
        @DisplayName("投稿者本人は年輪を論理削除できる（204）")
        void author_deletes_own_log_204() throws Exception {
            VillageEntity v = persistVillage();
            persistMembership(v.getId(), VILLAGER_ID, VillageRole.VILLAGER);
            VillageCalendarEventEntity event = persistCalendarEvent(v.getId());
            VillageCalendarEventLogEntity log = persistLog(event.getId(), 2026, null, "消す", VILLAGER_ID,
                    LocalDateTime.of(2026, 8, 1, 9, 0));

            authAs(VILLAGER_ID);
            mockMvc.perform(delete("/api/v1/villages/{vid}/calendar-events/{eid}/logs/{lid}",
                            v.getId(), event.getId(), log.getId()))
                    .andExpect(status().isNoContent());

            assertThat(logRepository.findById(log.getId())).get()
                    .satisfies(reloaded -> assertThat(reloaded.getDeletedAt()).isNotNull());
        }

        @Test
        @DisplayName("村長は他人の年輪を論理削除できる（204）")
        void headman_deletes_others_log_204() throws Exception {
            VillageEntity v = persistVillage();
            persistMembership(v.getId(), VILLAGER_ID, VillageRole.VILLAGER);
            persistMembership(v.getId(), HEADMAN_ID, VillageRole.HEADMAN);
            VillageCalendarEventEntity event = persistCalendarEvent(v.getId());
            VillageCalendarEventLogEntity log = persistLog(event.getId(), 2026, null, "村長が消す", VILLAGER_ID,
                    LocalDateTime.of(2026, 8, 1, 9, 0));

            authAs(HEADMAN_ID);
            mockMvc.perform(delete("/api/v1/villages/{vid}/calendar-events/{eid}/logs/{lid}",
                            v.getId(), event.getId(), log.getId()))
                    .andExpect(status().isNoContent());
        }

        @Test
        @DisplayName("長老は他人の年輪を論理削除できる（204）")
        void elder_deletes_others_log_204() throws Exception {
            VillageEntity v = persistVillage();
            persistMembership(v.getId(), VILLAGER_ID, VillageRole.VILLAGER);
            persistMembership(v.getId(), ELDER_ID, VillageRole.ELDER);
            VillageCalendarEventEntity event = persistCalendarEvent(v.getId());
            VillageCalendarEventLogEntity log = persistLog(event.getId(), 2026, null, "長老が消す", VILLAGER_ID,
                    LocalDateTime.of(2026, 8, 1, 9, 0));

            authAs(ELDER_ID);
            mockMvc.perform(delete("/api/v1/villages/{vid}/calendar-events/{eid}/logs/{lid}",
                            v.getId(), event.getId(), log.getId()))
                    .andExpect(status().isNoContent());
        }

        @Test
        @DisplayName("無関係な村人は他人の年輪を削除できない（403・VILLAGE_101 CALENDAR_LOG_FORBIDDEN）")
        void unrelated_villager_cannot_delete_403() throws Exception {
            VillageEntity v = persistVillage();
            persistMembership(v.getId(), VILLAGER_ID, VillageRole.VILLAGER);
            persistMembership(v.getId(), OTHER_VILLAGER_ID, VillageRole.VILLAGER);
            VillageCalendarEventEntity event = persistCalendarEvent(v.getId());
            VillageCalendarEventLogEntity log = persistLog(event.getId(), 2026, null, "守られる年輪", VILLAGER_ID,
                    LocalDateTime.of(2026, 8, 1, 9, 0));

            authAs(OTHER_VILLAGER_ID);
            mockMvc.perform(delete("/api/v1/villages/{vid}/calendar-events/{eid}/logs/{lid}",
                            v.getId(), event.getId(), log.getId()))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("VILLAGE_101"));

            assertThat(logRepository.findById(log.getId())).get()
                    .satisfies(reloaded -> assertThat(reloaded.getDeletedAt()).isNull());
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // IDOR（マスター御下命）
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("IDOR 村メンバーシップガード（非メンバーは 403）")
    class NonMemberGuard {

        @Test
        @DisplayName("非メンバーの年輪追加は 403（村メンバーシップガード）")
        void outsider_addLog_403() throws Exception {
            VillageEntity v = persistVillage();
            VillageCalendarEventEntity event = persistCalendarEvent(v.getId());

            authAs(OUTSIDER_ID); // membership を作らない
            mockMvc.perform(post("/api/v1/villages/{vid}/calendar-events/{eid}/logs", v.getId(), event.getId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(logBody(2026, null, "部外者の記録"))))
                    .andExpect(status().isForbidden());
        }
    }

    @Nested
    @DisplayName("IDOR クロス村（別村の calendarEventId を村IDと食い違わせると 404）")
    class CrossVillage {

        @Test
        @DisplayName("村A の URL で村B の歳時記へ年輪を追加すると 404（VILLAGE_056 CALENDAR_EVENT_NOT_FOUND）")
        void crossVillage_addLog_404() throws Exception {
            VillageEntity villageA = persistVillage();
            VillageEntity villageB = persistVillage();
            persistMembership(villageA.getId(), VILLAGER_ID, VillageRole.VILLAGER);
            // 歳時記は村B に属する
            VillageCalendarEventEntity eventB = persistCalendarEvent(villageB.getId());

            authAs(VILLAGER_ID);
            mockMvc.perform(post("/api/v1/villages/{vid}/calendar-events/{eid}/logs",
                            villageA.getId(), eventB.getId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(logBody(2026, null, "越境記録"))))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("VILLAGE_056"));
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

    /** POST .../logs のリクエストボディ（year 必須・photoR2Key/note 任意）。 */
    private Map<String, Object> logBody(int year, String photoR2Key, String note) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("year", year);
        if (photoR2Key != null) {
            body.put("photoR2Key", photoR2Key);
        }
        if (note != null) {
            body.put("note", note);
        }
        return body;
    }

    private VillageEntity persistVillage() {
        VillageEntity v = VillageEntity.builder()
                .slug("cal-" + Long.toHexString(System.nanoTime()))
                .name("年輪村" + System.nanoTime())
                .description("歳時記年輪テスト村")
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

    private VillageCalendarEventEntity persistCalendarEvent(UUID villageId) {
        VillageCalendarEventEntity e = VillageCalendarEventEntity.builder()
                .villageId(villageId)
                .title("夏祭り" + System.nanoTime())
                .description(null)
                .eventDate(LocalDate.of(2020, 7, 7))
                .eventEndDate(null)
                .isAnnualRecurring(true)
                .iconEmoji(null)
                .colorHex(null)
                .createdByUserId(HEADMAN_ID)
                .build();
        return calendarEventRepository.saveAndFlush(e);
    }

    private VillageCalendarEventLogEntity persistLog(UUID calendarEventId, int year, String photoR2Key,
                                                     String note, Long createdByUserId, LocalDateTime createdAt) {
        VillageCalendarEventLogEntity log = VillageCalendarEventLogEntity.builder()
                .calendarEventId(calendarEventId)
                .year(year)
                .photoR2Key(photoR2Key)
                .note(note)
                .createdByUserId(createdByUserId)
                .createdAt(createdAt)   // @PrePersist は createdAt==null のときのみ上書きする
                .build();
        return logRepository.saveAndFlush(log);
    }
}
