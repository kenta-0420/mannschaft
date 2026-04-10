package com.mannschaft.app.timetable;

import com.mannschaft.app.timetable.entity.TimetableChangeEntity;
import com.mannschaft.app.timetable.entity.TimetableEntity;
import com.mannschaft.app.timetable.entity.TimetableSlotEntity;
import com.mannschaft.app.timetable.repository.TimetableChangeRepository;
import com.mannschaft.app.timetable.repository.TimetableRepository;
import com.mannschaft.app.timetable.repository.TimetableSlotRepository;
import com.mannschaft.app.timetable.service.TimetableSlotService;
import com.mannschaft.app.timetable.service.TimetableSlotService.ResolvedSlot;
import com.mannschaft.app.timetable.service.TimetableSlotService.SlotData;
import com.mannschaft.app.common.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
@DisplayName("TimetableSlotService 単体テスト")
class TimetableSlotServiceTest {

    @Mock private TimetableSlotRepository slotRepository;
    @Mock private TimetableChangeRepository changeRepository;
    @Mock private TimetableRepository timetableRepository;
    @InjectMocks private TimetableSlotService service;

    @Nested
    @DisplayName("applyChanges")
    class ApplyChanges {

        @Test
        @DisplayName("正常系: DAY_OFFの場合空リストが返却される")
        void 変更適用_休日_空リスト() {
            // Given
            List<TimetableSlotEntity> slots = List.of(
                    TimetableSlotEntity.builder().timetableId(1L).dayOfWeek("MON")
                            .periodNumber(1).subjectName("数学").weekPattern(WeekPattern.EVERY).build());
            TimetableChangeEntity dayOff = TimetableChangeEntity.builder()
                    .timetableId(1L).targetDate(LocalDate.now())
                    .changeType(TimetableChangeType.DAY_OFF).build();
            List<TimetableChangeEntity> changes = List.of(dayOff);

            // When
            List<ResolvedSlot> result = service.applyChanges(slots, changes);

            // Then
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("正常系: 変更なしの場合スロットがそのまま返却される")
        void 変更適用_なし_そのまま() {
            // Given
            List<TimetableSlotEntity> slots = List.of(
                    TimetableSlotEntity.builder().timetableId(1L).dayOfWeek("MON")
                            .periodNumber(1).subjectName("数学").weekPattern(WeekPattern.EVERY).build());
            List<TimetableChangeEntity> changes = List.of();

            // When
            List<ResolvedSlot> result = service.applyChanges(slots, changes);

            // Then
            assertThat(result).hasSize(1);
            assertThat(result.get(0).subjectName()).isEqualTo("数学");
            assertThat(result.get(0).isChanged()).isFalse();
        }
    }

    @Nested
    @DisplayName("resolveWeekPattern")
    class ResolveWeekPattern {

        @Test
        @DisplayName("正常系: weekPatternEnabled=falseの場合EVERYが返却される")
        void パターン判定_無効_EVERY() {
            // Given
            TimetableEntity timetable = TimetableEntity.builder()
                    .teamId(1L).termId(1L).name("テスト")
                    .status(TimetableStatus.ACTIVE)
                    .visibility(TimetableVisibility.MEMBERS_ONLY)
                    .weekPatternEnabled(false).build();

            // When
            WeekPattern result = service.resolveWeekPattern(timetable, LocalDate.now());

            // Then
            assertThat(result).isEqualTo(WeekPattern.EVERY);
        }
    }

    @Nested
    @DisplayName("replaceSlots")
    class ReplaceSlots {

        @Test
        @DisplayName("異常系: EVERYとA/Bの共存でTIMETABLE_050例外")
        void 置換_パターン競合_例外() {
            // Given
            TimetableEntity draftTimetable = TimetableEntity.builder()
                    .teamId(1L).termId(1L).name("テスト")
                    .status(TimetableStatus.DRAFT)
                    .visibility(TimetableVisibility.MEMBERS_ONLY)
                    .weekPatternEnabled(false).build();
            given(timetableRepository.findById(1L)).willReturn(Optional.of(draftTimetable));

            List<SlotData> slots = List.of(
                    new SlotData("MON", 1, WeekPattern.EVERY, "数学", null, null, null, null),
                    new SlotData("MON", 1, WeekPattern.A, "体育", null, null, null, null));

            // When / Then
            assertThatThrownBy(() -> service.replaceSlots(1L, slots, null))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("TIMETABLE_050"));
        }
    }
}
