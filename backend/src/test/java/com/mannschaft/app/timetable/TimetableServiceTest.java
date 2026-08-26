package com.mannschaft.app.timetable;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.timetable.entity.TimetableEntity;
import com.mannschaft.app.timetable.entity.TimetableTermEntity;
import com.mannschaft.app.timetable.repository.TimetableRepository;
import com.mannschaft.app.timetable.repository.TimetableSlotRepository;
import com.mannschaft.app.timetable.repository.TimetableTermRepository;
import com.mannschaft.app.timetable.service.TimetableService;
import com.mannschaft.app.timetable.service.TimetableService.CreateTimetableData;
import com.mannschaft.app.timetable.service.TimetableService.UpdateTimetableData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.LocalDate;
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
    @Mock private AccessControlService accessControlService;
    @InjectMocks private TimetableService service;

    private static final Long TEAM_ID = 1L;
    private static final Long ACTOR_USER_ID = 100L;

    @Nested
    @DisplayName("getById")
    class GetById {

        @Test
        @DisplayName("異常系: 時間割不在でTIMETABLE_001例外")
        void 取得_不在_例外() {
            // Given
            given(timetableRepository.findByIdAndTeamId(1L, TEAM_ID)).willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> service.getById(1L, TEAM_ID, ACTOR_USER_ID))
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
            TimetableEntity result = service.create(TEAM_ID, data, ACTOR_USER_ID);

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
            assertThatThrownBy(() -> service.create(TEAM_ID, data, ACTOR_USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("TIMETABLE_002"));
        }
    }

    @Nested
    @DisplayName("update")
    class Update {

        @Test
        @DisplayName("回帰: updateはfindByIdAndTeamIdで取得した同一インスタンスをsaveする（toBuilderで新規行を作らない）")
        void 更新_同一インスタンスUPDATE() throws Exception {
            // Given
            TimetableEntity entity = TimetableEntity.builder()
                    .teamId(TEAM_ID).termId(1L).name("テスト時間割")
                    .status(TimetableStatus.DRAFT)
                    .visibility(TimetableVisibility.MEMBERS_ONLY)
                    .effectiveFrom(LocalDate.of(2025, 4, 1))
                    .effectiveUntil(LocalDate.of(2025, 7, 31))
                    .weekPatternEnabled(false).build();
            // id を反射でセット
            var idField = entity.getClass().getSuperclass().getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(entity, 20L);

            TimetableTermEntity term = TimetableTermEntity.builder()
                    .academicYear(2025).name("1学期")
                    .startDate(LocalDate.of(2025, 4, 1))
                    .endDate(LocalDate.of(2025, 7, 31)).sortOrder(1).build();

            UpdateTimetableData data = new UpdateTimetableData(
                    "更新後時間割", null, null, null, null, null, null, null);

            given(timetableRepository.findByIdAndTeamId(20L, TEAM_ID)).willReturn(Optional.of(entity));
            given(termRepository.findById(1L)).willReturn(Optional.of(term));
            given(timetableRepository.save(any(TimetableEntity.class))).willAnswer(inv -> inv.getArgument(0));

            // When
            service.update(20L, TEAM_ID, data, ACTOR_USER_ID);

            // Then
            // toBuilder().build() で別インスタンスを save していたら id=null の新規行 INSERT になる。
            // 同一インスタンスを save することで UPDATE になっていることを検証する。
            ArgumentCaptor<TimetableEntity> captor = ArgumentCaptor.forClass(TimetableEntity.class);
            verify(timetableRepository).save(captor.capture());
            assertThat(captor.getValue()).isSameAs(entity);
            // id が保持されている（= INSERT でなく UPDATE）
            assertThat(captor.getValue().getId()).isEqualTo(20L);
            // name が更新されている
            assertThat(captor.getValue().getName()).isEqualTo("更新後時間割");
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
            assertThatThrownBy(() -> service.activate(1L, TEAM_ID, ACTOR_USER_ID))
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
            assertThatThrownBy(() -> service.delete(1L, TEAM_ID, ACTOR_USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("TIMETABLE_011"));
        }
    }
}
