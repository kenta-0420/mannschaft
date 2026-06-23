package com.mannschaft.app.reflection.service;

import com.mannschaft.app.common.timezone.UserTimezoneCache;
import com.mannschaft.app.reflection.ReflectionLinkedSlotKind;
import com.mannschaft.app.reflection.ReflectionSourceType;
import com.mannschaft.app.reflection.dto.ReflectionTodayResponse;
import com.mannschaft.app.reflection.entity.ReflectionEntryEntity;
import com.mannschaft.app.reflection.entity.ReflectionThemeEntity;
import com.mannschaft.app.reflection.repository.ReflectionEntryRepository;
import com.mannschaft.app.reflection.repository.ReflectionThemeRepository;
import com.mannschaft.app.timetable.personal.dto.DashboardTimetableTodayResponse;
import com.mannschaft.app.timetable.personal.service.PersonalTimetableDashboardService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link ReflectionTodayService} 単体テスト（F06.5・§4.3 / §7 #12）。
 *
 * <p>カバー AC: AC-17（今日の全コマ縦並び・空コマも item 化）/ AC-19（時間割マスタ無改変＝ビュー組み立てのみ）/
 * 自由テーマ item（slotKind=null 列挙）/ コマ照合（source_kind/slot_id）/ 当日エントリのマスク状態付与 /
 * date 範囲検証（§2.5.1(c)）。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ReflectionTodayService 単体テスト")
class ReflectionTodayServiceTest {

    @Mock private ReflectionThemeRepository themeRepository;
    @Mock private ReflectionEntryRepository entryRepository;
    @Mock private PersonalTimetableDashboardService dashboardService;
    @Mock private ReflectionMaskEvaluator maskEvaluator;
    @Mock private UserTimezoneCache userTimezoneCache;

    @InjectMocks private ReflectionTodayService service;

    private static final Long USER_ID = 100L;
    private static final LocalDate TODAY = LocalDate.now();

    @BeforeEach
    void stubCommon() {
        lenient().when(userTimezoneCache.getTimezone(USER_ID)).thenReturn("Asia/Tokyo");
        lenient().when(maskEvaluator.isMasked(any(), any(), any())).thenReturn(false);
    }

    private static void setId(Object entity, UUID id) {
        try {
            Field f = entity.getClass().getSuperclass().getDeclaredField("id");
            f.setAccessible(true);
            f.set(entity, id);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** TEAM/PERSONAL コマ item を最小フィールドで組む（22 引数のうち照合に使う source_kind/slot_id/subject）。 */
    private DashboardTimetableTodayResponse.TimetableTodayItem slotItem(
            String sourceKind, Long slotId, String subject) {
        return new DashboardTimetableTodayResponse.TimetableTodayItem(
                sourceKind, null, null, null, null, slotId,
                "1限", 1, null, null, subject, null, null, null, null, null,
                null, Boolean.FALSE, null, Boolean.FALSE, null, Boolean.FALSE);
    }

    private DashboardTimetableTodayResponse dashboard(
            List<DashboardTimetableTodayResponse.TimetableTodayItem> items) {
        return new DashboardTimetableTodayResponse(TODAY, "EVERY", items);
    }

    private ReflectionThemeEntity theme(UUID id, ReflectionLinkedSlotKind kind, Long slotId,
                                        ReflectionSourceType sourceType) {
        ReflectionThemeEntity t = ReflectionThemeEntity.builder()
                .userId(USER_ID).title("数学" + slotId)
                .sourceType(sourceType)
                .linkedSlotKind(kind).linkedSlotId(slotId)
                .recallIntervalDays("1,3,7,14").build();
        setId(t, id);
        return t;
    }

    private ReflectionEntryEntity entry(UUID id, UUID themeId, LocalDate targetDate) {
        ReflectionEntryEntity e = ReflectionEntryEntity.builder()
                .themeId(themeId).userId(USER_ID).targetDate(targetDate)
                .structuredContent("{}").build();
        setId(e, id);
        return e;
    }

    @Test
    @DisplayName("AC-17/AC-19: コマを列挙し、theme は実呼び出しの dashboard から組み立てる（マスタ無改変）")
    void getToday_listsSlotsFromDashboard() {
        DashboardTimetableTodayResponse.TimetableTodayItem teamSlot = slotItem("TEAM", 11L, "数学");
        given(dashboardService.getTimetableToday(eq(USER_ID), any(LocalDate.class)))
                .willReturn(dashboard(List.of(teamSlot)));
        given(themeRepository.findByUserIdOrderByCreatedAtDesc(USER_ID)).willReturn(List.of());
        given(entryRepository.findByUserIdAndTargetDate(eq(USER_ID), any())).willReturn(List.of());

        ReflectionTodayResponse res = service.getToday(USER_ID, null);

        assertThat(res.items()).hasSize(1);
        ReflectionTodayResponse.ReflectionTodayItem item = res.items().get(0);
        assertThat(item.slotKind()).isEqualTo("TEAM");
        assertThat(item.slotId()).isEqualTo(11L);
        // theme 未設定の空きコマ → themeId null・hasEntryToday false（AC-17 空コマ編集可）
        assertThat(item.themeId()).isNull();
        assertThat(item.hasEntryToday()).isFalse();
    }

    @Test
    @DisplayName("コマ照合: linked_slot_kind/linked_slot_id が source_kind(String)/slot_id(Long) で一致したコマに theme/当日エントリを付与")
    void getToday_matchesThemeToSlot() {
        UUID themeId = UUID.randomUUID();
        UUID entryId = UUID.randomUUID();
        DashboardTimetableTodayResponse.TimetableTodayItem teamSlot = slotItem("TEAM", 11L, "数学");
        given(dashboardService.getTimetableToday(eq(USER_ID), any(LocalDate.class)))
                .willReturn(dashboard(List.of(teamSlot)));
        ReflectionThemeEntity linkedTheme = theme(themeId, ReflectionLinkedSlotKind.TEAM, 11L,
                ReflectionSourceType.SUBJECT);
        given(themeRepository.findByUserIdOrderByCreatedAtDesc(USER_ID)).willReturn(List.of(linkedTheme));
        given(entryRepository.findByUserIdAndTargetDate(eq(USER_ID), any()))
                .willReturn(List.of(entry(entryId, themeId, TODAY)));

        ReflectionTodayResponse res = service.getToday(USER_ID, null);

        ReflectionTodayResponse.ReflectionTodayItem item = res.items().get(0);
        assertThat(item.slotKind()).isEqualTo("TEAM");
        assertThat(item.slotId()).isEqualTo(11L);
        assertThat(item.themeId()).isEqualTo(themeId.toString());
        assertThat(item.hasEntryToday()).isTrue();
        assertThat(item.entryId()).isEqualTo(entryId.toString());
    }

    @Test
    @DisplayName("自由テーマ: linked_slot 無し（PROJECT/DIARY/FREE）の当日エントリ/テーマは slotKind=null item として列挙（§4.3）")
    void getToday_listsFreeThemesAsSlotKindNull() {
        UUID freeThemeId = UUID.randomUUID();
        given(dashboardService.getTimetableToday(eq(USER_ID), any(LocalDate.class)))
                .willReturn(dashboard(List.of()));
        ReflectionThemeEntity freeTheme = theme(freeThemeId, null, null, ReflectionSourceType.DIARY);
        given(themeRepository.findByUserIdOrderByCreatedAtDesc(USER_ID)).willReturn(List.of(freeTheme));
        given(entryRepository.findByUserIdAndTargetDate(eq(USER_ID), any())).willReturn(List.of());

        ReflectionTodayResponse res = service.getToday(USER_ID, null);

        assertThat(res.items()).hasSize(1);
        ReflectionTodayResponse.ReflectionTodayItem item = res.items().get(0);
        assertThat(item.slotKind()).isNull();
        assertThat(item.slotId()).isNull();
        assertThat(item.themeId()).isEqualTo(freeThemeId.toString());
    }

    @Test
    @DisplayName("当日エントリのマスク状態を付与する（maskEvaluator 経由・本文は出さない）")
    void getToday_appliesMaskStateToTodayEntry() {
        UUID themeId = UUID.randomUUID();
        UUID entryId = UUID.randomUUID();
        given(dashboardService.getTimetableToday(eq(USER_ID), any(LocalDate.class)))
                .willReturn(dashboard(List.of(slotItem("PERSONAL", 22L, "英語"))));
        ReflectionThemeEntity linkedTheme = theme(themeId, ReflectionLinkedSlotKind.PERSONAL, 22L,
                ReflectionSourceType.SUBJECT);
        given(themeRepository.findByUserIdOrderByCreatedAtDesc(USER_ID)).willReturn(List.of(linkedTheme));
        ReflectionEntryEntity todayEntry = entry(entryId, themeId, TODAY);
        given(entryRepository.findByUserIdAndTargetDate(eq(USER_ID), any()))
                .willReturn(List.of(todayEntry));
        given(maskEvaluator.isMasked(eq(todayEntry), eq(linkedTheme), any())).willReturn(true);

        ReflectionTodayResponse res = service.getToday(USER_ID, null);

        ReflectionTodayResponse.ReflectionTodayItem item = res.items().get(0);
        assertThat(item.hasEntryToday()).isTrue();
        assertThat(item.isMasked()).isTrue();
    }

    @Test
    @DisplayName("§2.5.1(c): ?date= が未来 30 日超なら 400（TARGET_DATE_OUT_OF_RANGE）")
    void getToday_dateTooFuture_throws() {
        assertThatThrownBy(() -> service.getToday(USER_ID, TODAY.plusDays(31)))
                .isInstanceOf(com.mannschaft.app.common.BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(com.mannschaft.app.reflection.ReflectionErrorCode.REFLECTION_TARGET_DATE_OUT_OF_RANGE);
    }

    @Test
    @DisplayName("?date= 指定時はその日を date として返し、dashboard へも当該日を渡す")
    void getToday_explicitDate_used() {
        LocalDate target = TODAY.minusDays(3);
        given(dashboardService.getTimetableToday(USER_ID, target)).willReturn(dashboard(List.of()));
        given(themeRepository.findByUserIdOrderByCreatedAtDesc(USER_ID)).willReturn(List.of());
        given(entryRepository.findByUserIdAndTargetDate(USER_ID, target)).willReturn(List.of());

        ReflectionTodayResponse res = service.getToday(USER_ID, target);

        assertThat(res.date()).isEqualTo(target);
    }

    // ===== AC-25: themeTitle / themeCreatedAt / lastReflectedAt =====

    @Test
    @DisplayName("AC-25: themeId を持つ item に themeTitle・themeCreatedAt・lastReflectedAt が載る")
    void getToday_themeMetaPopulatedForItemWithTheme() {
        UUID themeId = UUID.randomUUID();
        UUID entryId = UUID.randomUUID();
        LocalDate lastDate = TODAY.minusDays(2);

        DashboardTimetableTodayResponse.TimetableTodayItem teamSlot = slotItem("TEAM", 11L, "数学");
        given(dashboardService.getTimetableToday(eq(USER_ID), any(LocalDate.class)))
                .willReturn(dashboard(List.of(teamSlot)));

        ReflectionThemeEntity linkedTheme = themeWithCreatedAt(themeId, ReflectionLinkedSlotKind.TEAM, 11L,
                ReflectionSourceType.SUBJECT, LocalDateTime.of(2026, 1, 10, 9, 0));
        given(themeRepository.findByUserIdOrderByCreatedAtDesc(USER_ID)).willReturn(List.of(linkedTheme));
        given(entryRepository.findByUserIdAndTargetDate(eq(USER_ID), any()))
                .willReturn(List.of(entry(entryId, themeId, TODAY)));

        // AC-26: lastReflectedAt = MAX(targetDate) を ThemeLastDateView スタブで返す
        given(entryRepository.findLatestTargetDateByThemeIds(any()))
                .willReturn(List.of(themeLastDateView(themeId, lastDate)));

        ReflectionTodayResponse res = service.getToday(USER_ID, null);

        ReflectionTodayResponse.ReflectionTodayItem item = res.items().get(0);
        assertThat(item.themeId()).isEqualTo(themeId.toString());
        assertThat(item.themeTitle()).isEqualTo("数学" + 11L);
        assertThat(item.themeCreatedAt()).isEqualTo(LocalDate.of(2026, 1, 10));
        assertThat(item.lastReflectedAt()).isEqualTo(lastDate);
    }

    @Test
    @DisplayName("AC-25/AC-26: エントリなしテーマの lastReflectedAt は null")
    void getToday_lastReflectedAt_nullWhenNoEntries() {
        UUID themeId = UUID.randomUUID();

        given(dashboardService.getTimetableToday(eq(USER_ID), any(LocalDate.class)))
                .willReturn(dashboard(List.of()));
        ReflectionThemeEntity freeTheme = themeWithCreatedAt(themeId, null, null,
                ReflectionSourceType.DIARY, LocalDateTime.of(2026, 3, 1, 0, 0));
        given(themeRepository.findByUserIdOrderByCreatedAtDesc(USER_ID)).willReturn(List.of(freeTheme));
        given(entryRepository.findByUserIdAndTargetDate(eq(USER_ID), any())).willReturn(List.of());

        // GROUP BY クエリが themeId に対して行を返さない場合 → lastReflectedAt=null
        given(entryRepository.findLatestTargetDateByThemeIds(any())).willReturn(List.of());

        ReflectionTodayResponse res = service.getToday(USER_ID, null);

        ReflectionTodayResponse.ReflectionTodayItem item = res.items().get(0);
        assertThat(item.themeId()).isEqualTo(themeId.toString());
        assertThat(item.lastReflectedAt()).isNull();
    }

    @Test
    @DisplayName("AC-27: themeId を持つ item が0件なら findLatestTargetDateByThemeIds を呼ばない（N+1 回避）")
    void getToday_noThemeIds_noRepositoryCall() {
        // 全コマが空きコマ（themeId なし）
        DashboardTimetableTodayResponse.TimetableTodayItem emptySlot = slotItem("TEAM", 99L, "空き");
        given(dashboardService.getTimetableToday(eq(USER_ID), any(LocalDate.class)))
                .willReturn(dashboard(List.of(emptySlot)));
        given(themeRepository.findByUserIdOrderByCreatedAtDesc(USER_ID)).willReturn(List.of());
        given(entryRepository.findByUserIdAndTargetDate(eq(USER_ID), any())).willReturn(List.of());

        service.getToday(USER_ID, null);

        // themeId が空なので findLatestTargetDateByThemeIds は一切呼ばれない
        verify(entryRepository, never()).findLatestTargetDateByThemeIds(any());
    }

    @Test
    @DisplayName("AC-26: lastReflectedAt は当該テーマの最新 targetDate を選ぶ（最大値）")
    void getToday_lastReflectedAt_isMaxTargetDate() {
        UUID themeId = UUID.randomUUID();
        LocalDate olderDate = TODAY.minusDays(10);
        LocalDate newerDate = TODAY.minusDays(3);

        given(dashboardService.getTimetableToday(eq(USER_ID), any(LocalDate.class)))
                .willReturn(dashboard(List.of()));
        ReflectionThemeEntity freeTheme = themeWithCreatedAt(themeId, null, null,
                ReflectionSourceType.FREE, LocalDateTime.of(2026, 2, 1, 0, 0));
        given(themeRepository.findByUserIdOrderByCreatedAtDesc(USER_ID)).willReturn(List.of(freeTheme));
        given(entryRepository.findByUserIdAndTargetDate(eq(USER_ID), any())).willReturn(List.of());
        // MAX = newerDate を返す（GROUP BY 結果）
        given(entryRepository.findLatestTargetDateByThemeIds(any()))
                .willReturn(List.of(themeLastDateView(themeId, newerDate)));

        ReflectionTodayResponse res = service.getToday(USER_ID, null);

        assertThat(res.items().get(0).lastReflectedAt()).isEqualTo(newerDate);
    }

    @Test
    @DisplayName("AC-25: 空きコマ（themeId=null）には themeTitle・themeCreatedAt・lastReflectedAt が出ない")
    void getToday_emptySlot_noThemeMeta() {
        DashboardTimetableTodayResponse.TimetableTodayItem emptySlot = slotItem("TEAM", 77L, "体育");
        given(dashboardService.getTimetableToday(eq(USER_ID), any(LocalDate.class)))
                .willReturn(dashboard(List.of(emptySlot)));
        given(themeRepository.findByUserIdOrderByCreatedAtDesc(USER_ID)).willReturn(List.of());
        given(entryRepository.findByUserIdAndTargetDate(eq(USER_ID), any())).willReturn(List.of());

        ReflectionTodayResponse res = service.getToday(USER_ID, null);

        ReflectionTodayResponse.ReflectionTodayItem item = res.items().get(0);
        assertThat(item.themeId()).isNull();
        assertThat(item.themeTitle()).isNull();
        assertThat(item.themeCreatedAt()).isNull();
        assertThat(item.lastReflectedAt()).isNull();
    }

    // ===== ヘルパー（新テスト用） =====

    /** createdAt を指定できるテーマビルダー。 */
    private ReflectionThemeEntity themeWithCreatedAt(UUID id, ReflectionLinkedSlotKind kind, Long slotId,
                                                     ReflectionSourceType sourceType,
                                                     LocalDateTime createdAt) {
        ReflectionThemeEntity t = ReflectionThemeEntity.builder()
                .userId(USER_ID).title("数学" + slotId)
                .sourceType(sourceType)
                .linkedSlotKind(kind).linkedSlotId(slotId)
                .recallIntervalDays("1,3,7,14").build();
        t.setId(id);
        // createdAt は @PrePersist で設定されるがテストでは reflection で注入
        try {
            Field f = ReflectionThemeEntity.class.getDeclaredField("createdAt");
            f.setAccessible(true);
            f.set(t, createdAt);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return t;
    }

    /** ThemeLastDateView の匿名実装（テスト用スタブ）。 */
    private com.mannschaft.app.reflection.repository.ReflectionEntryRepository.ThemeLastDateView themeLastDateView(
            UUID themeId, LocalDate lastDate) {
        return new com.mannschaft.app.reflection.repository.ReflectionEntryRepository.ThemeLastDateView() {
            @Override public UUID getThemeId() { return themeId; }
            @Override public LocalDate getLastDate() { return lastDate; }
        };
    }

    // ===== Phase 2: AC-29 / AC-31 / AC-32 / AC-35 / AC-36 =====

    /**
     * AC-29: 条件B照合（科目名紐づけ）で、当日の同科目コマ数だけ item が生成される。
     * 各 item の subjectName はコマの科目名（テーマ名で上書きされない）。
     */
    @Test
    @DisplayName("AC-29(a): 科目紐づけテーマが当日同科目の全コマに item 化される")
    void getToday_conditionB_subjectMatchedThemeAppearsForEachSlot() {
        UUID themeId = UUID.randomUUID();
        // 同科目「数学」の2コマが存在する
        DashboardTimetableTodayResponse.TimetableTodayItem slot1 = slotItem("PERSONAL", 10L, "数学");
        DashboardTimetableTodayResponse.TimetableTodayItem slot2 = slotItem("PERSONAL", 20L, "数学");
        given(dashboardService.getTimetableToday(eq(USER_ID), any(LocalDate.class)))
                .willReturn(dashboard(List.of(slot1, slot2)));

        // linked_subject_name="数学"、linked_slot_id=null のテーマ
        ReflectionThemeEntity subjectTheme = themeBySubject(themeId, ReflectionLinkedSlotKind.PERSONAL,
                "数学", "MA101");
        given(themeRepository.findByUserIdOrderByCreatedAtDesc(USER_ID)).willReturn(List.of(subjectTheme));
        given(entryRepository.findByUserIdAndTargetDate(eq(USER_ID), any())).willReturn(List.of());
        lenient().when(entryRepository.findLatestTargetDateByThemeIds(any())).thenReturn(List.of());

        ReflectionTodayResponse res = service.getToday(USER_ID, null);

        // 2コマ両方に同テーマで item 化される
        assertThat(res.items()).hasSize(2);
        assertThat(res.items()).allSatisfy(item -> {
            assertThat(item.themeId()).isEqualTo(themeId.toString());
        });
    }

    /**
     * AC-29(b): 条件B由来 item の subjectName はコマの科目名（テーマ名で上書きされない）。
     */
    @Test
    @DisplayName("AC-29(b): 条件B由来 item の subjectName はコマの科目名（上書きしない）")
    void getToday_conditionB_subjectNameNotOverwritten() {
        UUID themeId = UUID.randomUUID();
        // コマの科目名は「数学I」
        DashboardTimetableTodayResponse.TimetableTodayItem slot = slotItem("PERSONAL", 10L, "数学I");
        given(dashboardService.getTimetableToday(eq(USER_ID), any(LocalDate.class)))
                .willReturn(dashboard(List.of(slot)));

        // テーマ名は「数学（私の勉強テーマ）」、subjectName紐づけ="数学I"
        ReflectionThemeEntity subjectTheme = themeBySubjectWithTitle(themeId,
                ReflectionLinkedSlotKind.PERSONAL, "数学I", null, "数学（私の勉強テーマ）");
        given(themeRepository.findByUserIdOrderByCreatedAtDesc(USER_ID)).willReturn(List.of(subjectTheme));
        given(entryRepository.findByUserIdAndTargetDate(eq(USER_ID), any())).willReturn(List.of());
        lenient().when(entryRepository.findLatestTargetDateByThemeIds(any())).thenReturn(List.of());

        ReflectionTodayResponse res = service.getToday(USER_ID, null);

        assertThat(res.items()).hasSize(1);
        // subjectName はコマの科目名であり、テーマ名で上書きされない
        assertThat(res.items().get(0).subjectName()).isEqualTo("数学I");
    }

    /**
     * AC-31: 数学1と数学A（courseCode相違）が別 bySubjectKey エントリとして照合される。
     */
    @Test
    @DisplayName("AC-31: courseCode 相違（数学1 vs 数学A）が別 bySubjectKey で別 item 化される")
    void getToday_conditionB_differentCourseCodesAreDistinct() {
        UUID themeId1 = UUID.randomUUID();
        UUID themeId2 = UUID.randomUUID();
        // 同科目名「数学」でcourseCode違いの2コマ
        DashboardTimetableTodayResponse.TimetableTodayItem slot1 =
                slotItemWithCourseCode("PERSONAL", 10L, "数学", "MA101");
        DashboardTimetableTodayResponse.TimetableTodayItem slot2 =
                slotItemWithCourseCode("PERSONAL", 20L, "数学", "MA201");
        given(dashboardService.getTimetableToday(eq(USER_ID), any(LocalDate.class)))
                .willReturn(dashboard(List.of(slot1, slot2)));

        // courseCode=MA101 に紐づくテーマ
        ReflectionThemeEntity theme1 = themeBySubject(themeId1, ReflectionLinkedSlotKind.PERSONAL, "数学", "MA101");
        // courseCode=MA201 に紐づくテーマ
        ReflectionThemeEntity theme2 = themeBySubject(themeId2, ReflectionLinkedSlotKind.PERSONAL, "数学", "MA201");
        given(themeRepository.findByUserIdOrderByCreatedAtDesc(USER_ID)).willReturn(List.of(theme1, theme2));
        given(entryRepository.findByUserIdAndTargetDate(eq(USER_ID), any())).willReturn(List.of());
        lenient().when(entryRepository.findLatestTargetDateByThemeIds(any())).thenReturn(List.of());

        ReflectionTodayResponse res = service.getToday(USER_ID, null);

        // slot1 → theme1、slot2 → theme2 でそれぞれ別の item
        assertThat(res.items()).hasSize(2);
        List<String> themeIds = res.items().stream().map(i -> i.themeId()).toList();
        assertThat(themeIds).containsExactlyInAnyOrder(themeId1.toString(), themeId2.toString());
    }

    /**
     * AC-32: 既存 linked_slot_id のみ持つテーマ（新カラム NULL）は従来通り条件A経路で動作。
     * subjectName はテーマ名で上書きされる（後方互換）。
     */
    @Test
    @DisplayName("AC-32: linked_slot_id のみテーマ（新カラムNULL）は条件A経路・subjectName はテーマ名で上書き")
    void getToday_conditionA_existingSlotTheme_backwardCompatible() {
        UUID themeId = UUID.randomUUID();
        // コマの科目名「数学II」、スロットID=11
        DashboardTimetableTodayResponse.TimetableTodayItem slot = slotItem("PERSONAL", 11L, "数学II");
        given(dashboardService.getTimetableToday(eq(USER_ID), any(LocalDate.class)))
                .willReturn(dashboard(List.of(slot)));

        // Phase1形式: linkedSlotId=11、linkedSubjectName=null
        ReflectionThemeEntity slotTheme = theme(themeId, ReflectionLinkedSlotKind.PERSONAL, 11L,
                ReflectionSourceType.SUBJECT);
        given(themeRepository.findByUserIdOrderByCreatedAtDesc(USER_ID)).willReturn(List.of(slotTheme));
        given(entryRepository.findByUserIdAndTargetDate(eq(USER_ID), any())).willReturn(List.of());
        lenient().when(entryRepository.findLatestTargetDateByThemeIds(any())).thenReturn(List.of());

        ReflectionTodayResponse res = service.getToday(USER_ID, null);

        assertThat(res.items()).hasSize(1);
        // 条件A: subjectName はテーマ名で上書き（既存動作保持）
        assertThat(res.items().get(0).subjectName()).isEqualTo("数学" + 11L); // theme.getTitle()
        assertThat(res.items().get(0).themeId()).isEqualTo(themeId.toString());
    }

    /**
     * AC-35: 条件B でコマに乗ったテーマは consumedThemeIds に追加されるため、
     * 自由テーマ枠（slotKind=null）に重複して出ない。
     */
    @Test
    @DisplayName("AC-35: 条件B で item 化されたテーマは自由テーマ枠に二重列挙されない")
    void getToday_conditionB_noDoubleListingInFreeThemes() {
        UUID themeId = UUID.randomUUID();
        DashboardTimetableTodayResponse.TimetableTodayItem slot = slotItem("PERSONAL", 10L, "数学");
        given(dashboardService.getTimetableToday(eq(USER_ID), any(LocalDate.class)))
                .willReturn(dashboard(List.of(slot)));

        // linked_subject_name="数学"、linked_slot_id=null → 条件B
        ReflectionThemeEntity subjectTheme = themeBySubject(themeId, ReflectionLinkedSlotKind.PERSONAL, "数学", null);
        given(themeRepository.findByUserIdOrderByCreatedAtDesc(USER_ID)).willReturn(List.of(subjectTheme));
        given(entryRepository.findByUserIdAndTargetDate(eq(USER_ID), any())).willReturn(List.of());
        lenient().when(entryRepository.findLatestTargetDateByThemeIds(any())).thenReturn(List.of());

        ReflectionTodayResponse res = service.getToday(USER_ID, null);

        // コマに1件だけ item 化され、自由テーマとして重複しない
        assertThat(res.items()).hasSize(1);
        assertThat(res.items().get(0).slotKind()).isEqualTo("PERSONAL");
    }

    /**
     * AC-36: 同一コマに条件A（slotId紐づけ）と条件B（科目名紐づけ）の両方がマッチする場合、
     * 条件A が優先され重複表示されない。
     */
    @Test
    @DisplayName("AC-36: 同一コマに条件A/B 両方マッチ時、条件A 優先で単一 item のみ返る")
    void getToday_conditionA_prioritizedOverConditionB() {
        UUID themeIdA = UUID.randomUUID(); // slotId紐づけテーマ（条件A）
        UUID themeIdB = UUID.randomUUID(); // subject紐づけテーマ（条件B）
        DashboardTimetableTodayResponse.TimetableTodayItem slot = slotItem("PERSONAL", 11L, "数学");
        given(dashboardService.getTimetableToday(eq(USER_ID), any(LocalDate.class)))
                .willReturn(dashboard(List.of(slot)));

        // 条件A: linked_slot_id=11
        ReflectionThemeEntity themeA = theme(themeIdA, ReflectionLinkedSlotKind.PERSONAL, 11L,
                ReflectionSourceType.SUBJECT);
        // 条件B: linked_subject_name="数学"、linked_slot_id=null
        ReflectionThemeEntity themeB = themeBySubject(themeIdB, ReflectionLinkedSlotKind.PERSONAL, "数学", null);
        given(themeRepository.findByUserIdOrderByCreatedAtDesc(USER_ID)).willReturn(List.of(themeA, themeB));
        given(entryRepository.findByUserIdAndTargetDate(eq(USER_ID), any())).willReturn(List.of());
        lenient().when(entryRepository.findLatestTargetDateByThemeIds(any())).thenReturn(List.of());

        ReflectionTodayResponse res = service.getToday(USER_ID, null);

        // 条件A優先のため item は1件のみ、themeId は条件Aのもの
        assertThat(res.items()).hasSize(1);
        assertThat(res.items().get(0).themeId()).isEqualTo(themeIdA.toString());
    }

    // ===== Phase 2 ヘルパー =====

    /**
     * 科目名紐づけテーマを生成（linked_subject_name 設定・linked_slot_id = null）。
     * タイトルは「テーマ:subjectName」で識別できるようにする。
     */
    private ReflectionThemeEntity themeBySubject(UUID id, ReflectionLinkedSlotKind kind,
                                                 String subjectName, String courseCode) {
        return themeBySubjectWithTitle(id, kind, subjectName, courseCode, "テーマ:" + subjectName);
    }

    /**
     * 科目名紐づけテーマをタイトル指定で生成。
     */
    private ReflectionThemeEntity themeBySubjectWithTitle(UUID id, ReflectionLinkedSlotKind kind,
                                                          String subjectName, String courseCode,
                                                          String title) {
        ReflectionThemeEntity t = ReflectionThemeEntity.builder()
                .userId(USER_ID)
                .title(title)
                .sourceType(ReflectionSourceType.SUBJECT)
                .linkedSlotKind(kind)
                .linkedSlotId(null)           // 条件B: slotId なし
                .linkedSubjectName(subjectName)
                .linkedCourseCode(courseCode)
                .recallIntervalDays("1,3,7,14")
                .build();
        setId(t, id);
        return t;
    }

    /** courseCode を含む TimetableTodayItem を生成するヘルパー。 */
    private DashboardTimetableTodayResponse.TimetableTodayItem slotItemWithCourseCode(
            String sourceKind, Long slotId, String subject, String courseCode) {
        return new DashboardTimetableTodayResponse.TimetableTodayItem(
                sourceKind, null, null, null, null, slotId,
                "1限", 1, null, null, subject, courseCode, null, null, null, null,
                null, Boolean.FALSE, null, Boolean.FALSE, null, Boolean.FALSE);
    }

    // ─── Phase 3: AC-39 / AC-45 ─────────────────────────────────────

    @Test
    @DisplayName("AC-39: アーカイブ済みテーマが今日ビューに出ない（findByUserIdAndArchivedAtIsNullOrderByCreatedAtDesc 切替）")
    void getToday_archivedThemeExcluded() {
        UUID archivedThemeId = UUID.randomUUID();
        // アーカイブ済みテーマ（archived_at != null）を含まないリストを返す（新メソッド模擬）
        given(themeRepository.findByUserIdAndArchivedAtIsNullOrderByCreatedAtDesc(USER_ID))
                .willReturn(List.of()); // アーカイブ済みは除外済みのリスト
        given(entryRepository.findByUserIdAndTargetDate(USER_ID, TODAY)).willReturn(List.of());
        given(entryRepository.findLatestTargetDateByThemeIds(any())).willReturn(List.of());
        given(dashboardService.getTimetableToday(USER_ID, TODAY))
                .willReturn(dashboard(List.of()));

        ReflectionTodayResponse response = service.getToday(USER_ID, TODAY);

        assertThat(response.items()).isEmpty();
        // 旧メソッド findByUserIdOrderByCreatedAtDesc は呼ばれない（切替検証）
        verify(themeRepository, never()).findByUserIdOrderByCreatedAtDesc(USER_ID);
        // 新メソッド findByUserIdAndArchivedAtIsNullOrderByCreatedAtDesc が呼ばれる
        verify(themeRepository).findByUserIdAndArchivedAtIsNullOrderByCreatedAtDesc(USER_ID);
    }

    @Test
    @DisplayName("AC-45: 後方互換 — archived_at が NULL の既存テーマは今日ビューに表示される")
    void getToday_activeThemeWithNullArchivedAt_displayed() {
        UUID themeId = UUID.randomUUID();
        // archived_at = null（アクティブ）のテーマ
        ReflectionThemeEntity activeTheme = theme(themeId, null, null, ReflectionSourceType.FREE);
        given(themeRepository.findByUserIdAndArchivedAtIsNullOrderByCreatedAtDesc(USER_ID))
                .willReturn(List.of(activeTheme));
        given(entryRepository.findByUserIdAndTargetDate(USER_ID, TODAY)).willReturn(List.of());
        given(entryRepository.findLatestTargetDateByThemeIds(any())).willReturn(List.of());
        given(dashboardService.getTimetableToday(USER_ID, TODAY))
                .willReturn(dashboard(List.of()));

        ReflectionTodayResponse response = service.getToday(USER_ID, TODAY);

        // 自由テーマとして 1 件表示される
        assertThat(response.items()).hasSize(1);
        assertThat(response.items().get(0).themeId()).isEqualTo(themeId.toString());
    }
}
