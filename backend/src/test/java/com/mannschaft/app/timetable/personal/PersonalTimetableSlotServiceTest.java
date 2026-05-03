package com.mannschaft.app.timetable.personal;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.timetable.WeekPattern;
import com.mannschaft.app.timetable.personal.dto.PersonalWeeklyViewResponse;
import com.mannschaft.app.timetable.personal.entity.PersonalTimetableEntity;
import com.mannschaft.app.timetable.personal.entity.PersonalTimetablePeriodEntity;
import com.mannschaft.app.timetable.personal.entity.PersonalTimetableSlotEntity;
import com.mannschaft.app.timetable.personal.repository.PersonalTimetablePeriodRepository;
import com.mannschaft.app.timetable.personal.repository.PersonalTimetableRepository;
import com.mannschaft.app.timetable.personal.repository.PersonalTimetableSlotRepository;
import com.mannschaft.app.timetable.personal.service.PersonalTimetableSlotService;
import com.mannschaft.app.timetable.personal.service.PersonalTimetableSlotService.SlotData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * F03.15 Phase 2 個人時間割コマサービスのユニットテスト。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PersonalTimetableSlotService ユニットテスト")
class PersonalTimetableSlotServiceTest {

    @Mock private PersonalTimetableRepository timetableRepository;
    @Mock private PersonalTimetableSlotRepository slotRepository;
    @Mock private PersonalTimetablePeriodRepository periodRepository;
    @InjectMocks private PersonalTimetableSlotService service;

    private static final Long USER_ID = 100L;
    private static final Long TIMETABLE_ID = 1L;

    private PersonalTimetableEntity draft;
    private PersonalTimetableEntity active;
    private PersonalTimetableEntity draftWithWeekPattern;

    @BeforeEach
    void setUp() {
        draft = PersonalTimetableEntity.builder()
                .userId(USER_ID)
                .name("テスト")
                .effectiveFrom(LocalDate.of(2026, 4, 1))
                .effectiveUntil(LocalDate.of(2026, 9, 30))
                .status(PersonalTimetableStatus.DRAFT)
                .visibility(PersonalTimetableVisibility.PRIVATE)
                .weekPatternEnabled(false)
                .build();
        active = draft.toBuilder().status(PersonalTimetableStatus.ACTIVE).build();
        draftWithWeekPattern = draft.toBuilder()
                .weekPatternEnabled(true)
                .weekPatternBaseDate(LocalDate.of(2026, 4, 6))
                .build();
    }

    private SlotData slot(String dow, int period, WeekPattern wp) {
        return new SlotData(dow, period, wp, "国語", null, null, null, null, null,
                null, null, null, null, null);
    }

    private SlotData slotWithLink(Long teamId) {
        return new SlotData("MON", 1, WeekPattern.EVERY, "国語", null, null, null, null, null,
                teamId, null, null, null, null);
    }

    @Test
    @DisplayName("list: 所有者検証 NG で 404 例外")
    void list_他人IDで404() {
        given(timetableRepository.findByIdAndUserIdAndDeletedAtIsNull(TIMETABLE_ID, USER_ID))
                .willReturn(Optional.empty());
        assertThatThrownBy(() -> service.list(TIMETABLE_ID, USER_ID, null))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("replaceAll: ACTIVE は 409 例外で削除も保存も発生しない")
    void replaceAll_ACTIVEで409() {
        given(timetableRepository.findByIdAndUserIdAndDeletedAtIsNull(TIMETABLE_ID, USER_ID))
                .willReturn(Optional.of(active));
        List<SlotData> data = List.of(slot("MON", 1, WeekPattern.EVERY));
        assertThatThrownBy(() -> service.replaceAll(TIMETABLE_ID, USER_ID, null, data))
                .isInstanceOf(BusinessException.class);
        verify(slotRepository, never()).deleteByPersonalTimetableId(anyLong());
        verify(slotRepository, never()).saveAll(anyList());
    }

    @Test
    @DisplayName("replaceAll: リンク付き要素は Phase 2 では 400 拒否")
    void replaceAll_リンク付き要素で400() {
        given(timetableRepository.findByIdAndUserIdAndDeletedAtIsNull(TIMETABLE_ID, USER_ID))
                .willReturn(Optional.of(draft));
        assertThatThrownBy(() -> service.replaceAll(TIMETABLE_ID, USER_ID, null,
                List.of(slotWithLink(42L))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Phase 4");
    }

    @Test
    @DisplayName("replaceAll: 100件超で 409")
    void replaceAll_100件超で409() {
        given(timetableRepository.findByIdAndUserIdAndDeletedAtIsNull(TIMETABLE_ID, USER_ID))
                .willReturn(Optional.of(draft));
        List<SlotData> data = new java.util.ArrayList<>();
        // 異なるキーで 101 件作る（period 番号は 1〜15、曜日は 7、合計 105）
        outer:
        for (int p = 1; p <= 15; p++) {
            for (String dow : List.of("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN")) {
                data.add(slot(dow, p, WeekPattern.EVERY));
                if (data.size() == 101) break outer;
            }
        }
        assertThatThrownBy(() -> service.replaceAll(TIMETABLE_ID, USER_ID, null, data))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("最大100件");
    }

    @Test
    @DisplayName("replaceAll: 週パターン無効時に A/B 指定で 422")
    void replaceAll_週パターン無効でA指定例外() {
        given(timetableRepository.findByIdAndUserIdAndDeletedAtIsNull(TIMETABLE_ID, USER_ID))
                .willReturn(Optional.of(draft));
        // 時限定義は空（時限存在チェック自体は時限が登録済みの場合のみ走る）
        given(periodRepository.findByPersonalTimetableIdOrderByPeriodNumberAsc(TIMETABLE_ID))
                .willReturn(List.of());

        List<SlotData> data = List.of(slot("MON", 1, WeekPattern.A));
        assertThatThrownBy(() -> service.replaceAll(TIMETABLE_ID, USER_ID, null, data))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("週パターン無効");
    }

    @Test
    @DisplayName("replaceAll: EVERY と A の同時登録は 422")
    void replaceAll_EVERYとAの同時登録で例外() {
        given(timetableRepository.findByIdAndUserIdAndDeletedAtIsNull(TIMETABLE_ID, USER_ID))
                .willReturn(Optional.of(draftWithWeekPattern));
        given(periodRepository.findByPersonalTimetableIdOrderByPeriodNumberAsc(TIMETABLE_ID))
                .willReturn(List.of());
        List<SlotData> data = List.of(
                slot("MON", 1, WeekPattern.EVERY),
                slot("MON", 1, WeekPattern.A));
        assertThatThrownBy(() -> service.replaceAll(TIMETABLE_ID, USER_ID, null, data))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("replaceAll: 完全重複（同一 day×period×EVERY）は 422")
    void replaceAll_完全重複で例外() {
        given(timetableRepository.findByIdAndUserIdAndDeletedAtIsNull(TIMETABLE_ID, USER_ID))
                .willReturn(Optional.of(draft));
        given(periodRepository.findByPersonalTimetableIdOrderByPeriodNumberAsc(TIMETABLE_ID))
                .willReturn(List.of());
        List<SlotData> data = List.of(
                slot("MON", 1, WeekPattern.EVERY),
                slot("MON", 1, WeekPattern.EVERY));
        assertThatThrownBy(() -> service.replaceAll(TIMETABLE_ID, USER_ID, null, data))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("重複");
    }

    @Test
    @DisplayName("replaceAll: is_break 時限への割当で 422")
    void replaceAll_break時限への割当で例外() {
        given(timetableRepository.findByIdAndUserIdAndDeletedAtIsNull(TIMETABLE_ID, USER_ID))
                .willReturn(Optional.of(draft));
        PersonalTimetablePeriodEntity breakPeriod = PersonalTimetablePeriodEntity.builder()
                .personalTimetableId(TIMETABLE_ID)
                .periodNumber(1)
                .label("休憩")
                .startTime(LocalTime.of(10, 30))
                .endTime(LocalTime.of(10, 40))
                .isBreak(true)
                .build();
        given(periodRepository.findByPersonalTimetableIdOrderByPeriodNumberAsc(TIMETABLE_ID))
                .willReturn(List.of(breakPeriod));
        List<SlotData> data = List.of(slot("MON", 1, WeekPattern.EVERY));
        assertThatThrownBy(() -> service.replaceAll(TIMETABLE_ID, USER_ID, null, data))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("休憩");
    }

    @Test
    @DisplayName("replaceAll: 時限が定義済みで未登録番号を指定すると 422")
    void replaceAll_未登録時限番号で例外() {
        given(timetableRepository.findByIdAndUserIdAndDeletedAtIsNull(TIMETABLE_ID, USER_ID))
                .willReturn(Optional.of(draft));
        PersonalTimetablePeriodEntity p1 = PersonalTimetablePeriodEntity.builder()
                .personalTimetableId(TIMETABLE_ID)
                .periodNumber(1)
                .label("1限")
                .startTime(LocalTime.of(9, 0))
                .endTime(LocalTime.of(10, 30))
                .isBreak(false)
                .build();
        given(periodRepository.findByPersonalTimetableIdOrderByPeriodNumberAsc(TIMETABLE_ID))
                .willReturn(List.of(p1));
        List<SlotData> data = List.of(slot("MON", 5, WeekPattern.EVERY));
        assertThatThrownBy(() -> service.replaceAll(TIMETABLE_ID, USER_ID, null, data))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("時限");
    }

    @Test
    @DisplayName("replaceAll: 全曜日全置換の正常系")
    void replaceAll_全曜日正常系() {
        given(timetableRepository.findByIdAndUserIdAndDeletedAtIsNull(TIMETABLE_ID, USER_ID))
                .willReturn(Optional.of(draft));
        given(periodRepository.findByPersonalTimetableIdOrderByPeriodNumberAsc(TIMETABLE_ID))
                .willReturn(List.of());
        given(slotRepository.saveAll(anyList())).willAnswer(inv -> inv.getArgument(0));

        List<SlotData> data = List.of(
                slot("MON", 1, WeekPattern.EVERY),
                slot("TUE", 1, WeekPattern.EVERY));

        List<PersonalTimetableSlotEntity> result =
                service.replaceAll(TIMETABLE_ID, USER_ID, null, data);

        assertThat(result).hasSize(2);
        verify(slotRepository).deleteByPersonalTimetableId(TIMETABLE_ID);
        verify(slotRepository, never()).deleteByPersonalTimetableIdAndDayOfWeek(anyLong(), anyString());
    }

    @Test
    @DisplayName("replaceAll: 曜日指定時はその曜日のみ削除→挿入")
    void replaceAll_曜日指定で部分置換() {
        given(timetableRepository.findByIdAndUserIdAndDeletedAtIsNull(TIMETABLE_ID, USER_ID))
                .willReturn(Optional.of(draft));
        given(slotRepository.countByPersonalTimetableId(TIMETABLE_ID)).willReturn(5L);
        given(slotRepository.findByPersonalTimetableIdAndDayOfWeekOrderByPeriodNumberAsc(
                TIMETABLE_ID, "MON")).willReturn(List.of());
        given(periodRepository.findByPersonalTimetableIdOrderByPeriodNumberAsc(TIMETABLE_ID))
                .willReturn(List.of());
        given(slotRepository.saveAll(anyList())).willAnswer(inv -> inv.getArgument(0));

        List<SlotData> data = List.of(slot("MON", 1, WeekPattern.EVERY));
        service.replaceAll(TIMETABLE_ID, USER_ID, "MON", data);

        verify(slotRepository).deleteByPersonalTimetableIdAndDayOfWeek(TIMETABLE_ID, "MON");
        verify(slotRepository, never()).deleteByPersonalTimetableId(anyLong());
    }

    @Test
    @DisplayName("resolveWeekPattern: weekPatternEnabled=false なら EVERY")
    void resolveWeekPattern_無効ならEVERY() {
        WeekPattern result = service.resolveWeekPattern(draft, LocalDate.of(2026, 5, 1));
        assertThat(result).isEqualTo(WeekPattern.EVERY);
    }

    @Test
    @DisplayName("resolveWeekPattern: 偶数週オフセットは A、奇数は B")
    void resolveWeekPattern_AB判定() {
        // base = 2026-04-06 (月)
        WeekPattern w0 = service.resolveWeekPattern(draftWithWeekPattern, LocalDate.of(2026, 4, 6));
        WeekPattern w1 = service.resolveWeekPattern(draftWithWeekPattern, LocalDate.of(2026, 4, 13));
        assertThat(w0).isEqualTo(WeekPattern.A);
        assertThat(w1).isEqualTo(WeekPattern.B);
    }

    @Test
    @DisplayName("getWeeklyView: 7日分のキーが返り、A/B 週で MON のフィルタが効く")
    void getWeeklyView_7日構造() {
        given(timetableRepository.findByIdAndUserIdAndDeletedAtIsNull(TIMETABLE_ID, USER_ID))
                .willReturn(Optional.of(draftWithWeekPattern));

        // MON に EVERY と B のスロット 1 件ずつ
        PersonalTimetableSlotEntity mEvery = PersonalTimetableSlotEntity.builder()
                .personalTimetableId(TIMETABLE_ID)
                .dayOfWeek("MON").periodNumber(1).weekPattern(WeekPattern.EVERY)
                .subjectName("毎週").build();
        PersonalTimetableSlotEntity mB = PersonalTimetableSlotEntity.builder()
                .personalTimetableId(TIMETABLE_ID)
                .dayOfWeek("MON").periodNumber(2).weekPattern(WeekPattern.B)
                .subjectName("B週のみ").build();
        given(slotRepository.findByPersonalTimetableIdOrderByDayOfWeekAscPeriodNumberAsc(TIMETABLE_ID))
                .willReturn(List.of(mEvery, mB));
        given(periodRepository.findByPersonalTimetableIdOrderByPeriodNumberAsc(TIMETABLE_ID))
                .willReturn(List.of());

        // 2026-04-06 の週は base と同じなので A 週 → B 指定スロットは MON から除外される
        PersonalWeeklyViewResponse view =
                service.getWeeklyView(TIMETABLE_ID, USER_ID, LocalDate.of(2026, 4, 6));

        assertThat(view.weekStart()).isEqualTo(LocalDate.of(2026, 4, 6));
        assertThat(view.days()).hasSize(7);
        assertThat(view.days().get("MON").slots()).hasSize(1);
        assertThat(view.days().get("MON").slots().get(0).subjectName()).isEqualTo("毎週");
        assertThat(view.currentWeekPattern()).isEqualTo("A");
    }

    @Test
    @DisplayName("listToday: 今日の曜日でフィルタされ A/B 週も適用される")
    void listToday_曜日とAB適用() {
        // 環境依存をなくすため、今日の曜日を取得してそれに合わせたモックを構築
        String todayDow = LocalDate.now().getDayOfWeek().name().substring(0, 3);
        given(timetableRepository.findByIdAndUserIdAndDeletedAtIsNull(TIMETABLE_ID, USER_ID))
                .willReturn(Optional.of(draft));  // weekPatternEnabled=false → EVERY のみ通る

        PersonalTimetableSlotEntity todaySlot = PersonalTimetableSlotEntity.builder()
                .personalTimetableId(TIMETABLE_ID)
                .dayOfWeek(todayDow).periodNumber(1).weekPattern(WeekPattern.EVERY)
                .subjectName("今日").build();
        given(slotRepository.findByPersonalTimetableIdAndDayOfWeekOrderByPeriodNumberAsc(
                eq(TIMETABLE_ID), eq(todayDow)))
                .willReturn(List.of(todaySlot));

        List<PersonalTimetableSlotEntity> result = service.listToday(TIMETABLE_ID, USER_ID);
        assertThat(result).hasSize(1).extracting(PersonalTimetableSlotEntity::getSubjectName)
                .containsExactly("今日");
    }
}
