package com.mannschaft.app.timetable;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.timetable.entity.TimetableEntity;
import com.mannschaft.app.timetable.entity.TimetableSlotEntity;
import com.mannschaft.app.timetable.entity.TimetableTermEntity;
import com.mannschaft.app.timetable.repository.TimetableRepository;
import com.mannschaft.app.timetable.repository.TimetableSlotRepository;
import com.mannschaft.app.timetable.repository.TimetableTermRepository;
import com.mannschaft.app.timetable.service.TimetableService;
import com.mannschaft.app.timetable.service.TimetableService.CreateTimetableData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("TimetableService 単体テスト")
class TimetableServiceTest {

    @Mock private TimetableRepository timetableRepository;
    @Mock private TimetableSlotRepository slotRepository;
    @Mock private TimetableTermRepository termRepository;
    @Mock private ApplicationEventPublisher eventPublisher;
    @InjectMocks private TimetableService service;

    private static final Long TEAM_ID = 1L;

    @Nested
    @DisplayName("getById")
    class GetById {

        @Test
        @DisplayName("異常系: 時間割不在でTIMETABLE_001例外")
        void 取得_不在_例外() {
            // Given
            given(timetableRepository.findByIdAndTeamId(1L, TEAM_ID)).willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> service.getById(1L, TEAM_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("TIMETABLE_001"));
        }
    }

    @Nested
    @DisplayName("create")
    class Create {

        @Test
        @DisplayName("正常系: 時間割が作成される")
        void 作成_正常_保存() {
            // Given
            TimetableTermEntity term = TimetableTermEntity.builder()
                    .academicYear(2025).name("1学期")
                    .startDate(LocalDate.of(2025, 4, 1))
                    .endDate(LocalDate.of(2025, 7, 31)).build();
            given(termRepository.findById(1L)).willReturn(Optional.of(term));
            given(timetableRepository.save(any(TimetableEntity.class))).willAnswer(inv -> inv.getArgument(0));

            CreateTimetableData data = new CreateTimetableData(
                    1L, "テスト時間割", TimetableVisibility.MEMBERS_ONLY,
                    LocalDate.of(2025, 4, 1), LocalDate.of(2025, 7, 31),
                    false, null, null, null, 100L);

            // When
            TimetableEntity result = service.create(TEAM_ID, data);

            // Then
            assertThat(result.getName()).isEqualTo("テスト時間割");
            verify(timetableRepository).save(any(TimetableEntity.class));
        }

        @Test
        @DisplayName("異常系: 学期不在でTIMETABLE_002例外")
        void 作成_学期不在_例外() {
            // Given
            given(termRepository.findById(1L)).willReturn(Optional.empty());

            CreateTimetableData data = new CreateTimetableData(
                    1L, "テスト", null, null, null, false, null, null, null, 100L);

            // When / Then
            assertThatThrownBy(() -> service.create(TEAM_ID, data))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("TIMETABLE_002"));
        }
    }

    @Nested
    @DisplayName("activate")
    class Activate {

        @Test
        @DisplayName("異常系: DRAFT以外の場合TIMETABLE_011例外")
        void 有効化_非下書き_例外() {
            // Given
            TimetableEntity entity = TimetableEntity.builder()
                    .teamId(TEAM_ID).termId(1L).name("テスト")
                    .status(TimetableStatus.ACTIVE)
                    .visibility(TimetableVisibility.MEMBERS_ONLY).build();
            given(timetableRepository.findByIdAndTeamId(1L, TEAM_ID)).willReturn(Optional.of(entity));

            // When / Then
            assertThatThrownBy(() -> service.activate(1L, TEAM_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("TIMETABLE_011"));
        }
    }

    @Nested
    @DisplayName("delete")
    class Delete {

        @Test
        @DisplayName("異常系: DRAFT以外は削除不可でTIMETABLE_011例外")
        void 削除_非下書き_例外() {
            // Given
            TimetableEntity entity = TimetableEntity.builder()
                    .teamId(TEAM_ID).termId(1L).name("テスト")
                    .status(TimetableStatus.ACTIVE)
                    .visibility(TimetableVisibility.MEMBERS_ONLY).build();
            given(timetableRepository.findByIdAndTeamId(1L, TEAM_ID)).willReturn(Optional.of(entity));

            // When / Then
            assertThatThrownBy(() -> service.delete(1L, TEAM_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("TIMETABLE_011"));
        }
    }
}
