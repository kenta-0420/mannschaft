package com.mannschaft.app.timetable.personal;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.timetable.personal.entity.PersonalTimetableEntity;
import com.mannschaft.app.timetable.personal.entity.PersonalTimetablePeriodEntity;
import com.mannschaft.app.timetable.personal.repository.PersonalTimetablePeriodRepository;
import com.mannschaft.app.timetable.personal.repository.PersonalTimetableRepository;
import com.mannschaft.app.timetable.personal.service.PersonalTimetablePeriodService;
import com.mannschaft.app.timetable.personal.service.PersonalTimetablePeriodService.PeriodData;
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
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * F03.15 Phase 2 個人時間割時限定義サービスのユニットテスト。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PersonalTimetablePeriodService ユニットテスト")
class PersonalTimetablePeriodServiceTest {

    @Mock private PersonalTimetableRepository timetableRepository;
    @Mock private PersonalTimetablePeriodRepository periodRepository;
    @InjectMocks private PersonalTimetablePeriodService service;

    private static final Long USER_ID = 100L;
    private static final Long TIMETABLE_ID = 1L;

    private PersonalTimetableEntity draft;
    private PersonalTimetableEntity active;

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
    }

    @Test
    @DisplayName("list: 所有者検証 NG（他人ID）で 404 例外")
    void list_他人IDで404() {
        given(timetableRepository.findByIdAndUserIdAndDeletedAtIsNull(TIMETABLE_ID, USER_ID))
                .willReturn(Optional.empty());
        assertThatThrownBy(() -> service.list(TIMETABLE_ID, USER_ID))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("replaceAll: DRAFT 以外は 409 例外で削除も保存も発生しない")
    void replaceAll_DRAFT以外で409() {
        given(timetableRepository.findByIdAndUserIdAndDeletedAtIsNull(TIMETABLE_ID, USER_ID))
                .willReturn(Optional.of(active));
        List<PeriodData> data = List.of(
                new PeriodData(1, "1限", LocalTime.of(9, 0), LocalTime.of(10, 30), false));

        assertThatThrownBy(() -> service.replaceAll(TIMETABLE_ID, USER_ID, data))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("下書き状態");
        verify(periodRepository, never()).deleteByPersonalTimetableId(anyLong());
        verify(periodRepository, never()).saveAll(anyList());
    }

    @Test
    @DisplayName("replaceAll: 上限15件超過で 409 例外")
    void replaceAll_15件超過で409() {
        given(timetableRepository.findByIdAndUserIdAndDeletedAtIsNull(TIMETABLE_ID, USER_ID))
                .willReturn(Optional.of(draft));
        List<PeriodData> data = new java.util.ArrayList<>();
        for (int i = 1; i <= 16; i++) {
            data.add(new PeriodData(i, "P" + i,
                    LocalTime.of(8, 0).plusMinutes((i - 1) * 50L),
                    LocalTime.of(8, 45).plusMinutes((i - 1) * 50L),
                    false));
        }
        assertThatThrownBy(() -> service.replaceAll(TIMETABLE_ID, USER_ID, data))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("最大15件");
    }

    @Test
    @DisplayName("replaceAll: start_time >= end_time は 422 相当の例外")
    void replaceAll_時刻逆転で例外() {
        given(timetableRepository.findByIdAndUserIdAndDeletedAtIsNull(TIMETABLE_ID, USER_ID))
                .willReturn(Optional.of(draft));
        List<PeriodData> data = List.of(
                new PeriodData(1, "1限", LocalTime.of(10, 0), LocalTime.of(9, 0), false));
        assertThatThrownBy(() -> service.replaceAll(TIMETABLE_ID, USER_ID, data))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("replaceAll: period_number 重複で例外")
    void replaceAll_period番号重複で例外() {
        given(timetableRepository.findByIdAndUserIdAndDeletedAtIsNull(TIMETABLE_ID, USER_ID))
                .willReturn(Optional.of(draft));
        List<PeriodData> data = List.of(
                new PeriodData(1, "1限", LocalTime.of(9, 0), LocalTime.of(10, 0), false),
                new PeriodData(1, "重複", LocalTime.of(11, 0), LocalTime.of(12, 0), false));
        assertThatThrownBy(() -> service.replaceAll(TIMETABLE_ID, USER_ID, data))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("重複");
    }

    @Test
    @DisplayName("replaceAll: period_number 範囲外（0 や 16）で例外")
    void replaceAll_period番号範囲外で例外() {
        given(timetableRepository.findByIdAndUserIdAndDeletedAtIsNull(TIMETABLE_ID, USER_ID))
                .willReturn(Optional.of(draft));
        List<PeriodData> data = List.of(
                new PeriodData(0, "out", LocalTime.of(9, 0), LocalTime.of(10, 0), false));
        assertThatThrownBy(() -> service.replaceAll(TIMETABLE_ID, USER_ID, data))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("1〜15");
    }

    @Test
    @DisplayName("replaceAll: 正常系 — 削除→保存が呼ばれる")
    void replaceAll_正常系() {
        given(timetableRepository.findByIdAndUserIdAndDeletedAtIsNull(TIMETABLE_ID, USER_ID))
                .willReturn(Optional.of(draft));
        List<PeriodData> data = List.of(
                new PeriodData(1, "1限", LocalTime.of(9, 0), LocalTime.of(10, 30), false),
                new PeriodData(2, "2限", LocalTime.of(10, 40), LocalTime.of(12, 10), false));
        given(periodRepository.saveAll(anyList())).willAnswer(inv -> inv.getArgument(0));

        List<PersonalTimetablePeriodEntity> result = service.replaceAll(TIMETABLE_ID, USER_ID, data);

        assertThat(result).hasSize(2);
        verify(periodRepository).deleteByPersonalTimetableId(TIMETABLE_ID);
        verify(periodRepository).saveAll(anyList());
    }

    @Test
    @DisplayName("replaceAll: 空配列でも DELETE は実行され、保存はスキップされる")
    void replaceAll_空配列はDELETEのみ() {
        given(timetableRepository.findByIdAndUserIdAndDeletedAtIsNull(TIMETABLE_ID, USER_ID))
                .willReturn(Optional.of(draft));

        List<PersonalTimetablePeriodEntity> result =
                service.replaceAll(TIMETABLE_ID, USER_ID, List.of());

        assertThat(result).isEmpty();
        verify(periodRepository).deleteByPersonalTimetableId(TIMETABLE_ID);
        verify(periodRepository, never()).saveAll(anyList());
    }

    @Test
    @DisplayName("applyTemplate: UNIV_90MIN は 6 件（5授業 + 1 break）の期待値で投入される")
    void applyTemplate_UNIV90MIN投入() {
        given(timetableRepository.findByIdAndUserIdAndDeletedAtIsNull(TIMETABLE_ID, USER_ID))
                .willReturn(Optional.of(draft));
        given(periodRepository.saveAll(anyList())).willAnswer(inv -> inv.getArgument(0));

        List<PersonalTimetablePeriodEntity> result =
                service.applyTemplate(TIMETABLE_ID, USER_ID, PersonalPeriodTemplate.UNIV_90MIN);

        assertThat(result).hasSize(PersonalPeriodTemplate.UNIV_90MIN.getEntries().size());
        verify(periodRepository).deleteByPersonalTimetableId(TIMETABLE_ID);
    }

    @Test
    @DisplayName("applyTemplate: CUSTOM は何も投入しない（既存をそのまま list する）")
    void applyTemplate_CUSTOMは投入しない() {
        given(timetableRepository.findByIdAndUserIdAndDeletedAtIsNull(TIMETABLE_ID, USER_ID))
                .willReturn(Optional.of(draft));
        given(periodRepository.findByPersonalTimetableIdOrderByPeriodNumberAsc(TIMETABLE_ID))
                .willReturn(List.of());

        List<PersonalTimetablePeriodEntity> result =
                service.applyTemplate(TIMETABLE_ID, USER_ID, PersonalPeriodTemplate.CUSTOM);

        assertThat(result).isEmpty();
        verify(periodRepository, never()).deleteByPersonalTimetableId(anyLong());
        verify(periodRepository, never()).saveAll(anyList());
    }
}
