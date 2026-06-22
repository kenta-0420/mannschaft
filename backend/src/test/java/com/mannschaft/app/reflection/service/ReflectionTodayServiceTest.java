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
}
