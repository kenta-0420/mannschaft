package com.mannschaft.app.timetable;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.timetable.entity.TimetableChangeEntity;
import com.mannschaft.app.timetable.entity.TimetableEntity;
import com.mannschaft.app.timetable.repository.TimetableChangeRepository;
import com.mannschaft.app.timetable.repository.TimetableRepository;
import com.mannschaft.app.timetable.service.TimetableChangeService;
import com.mannschaft.app.timetable.service.TimetableChangeService.CreateChangeData;
import com.mannschaft.app.timetable.service.TimetableChangeService.UpdateChangeData;
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
@DisplayName("TimetableChangeService 単体テスト")
class TimetableChangeServiceTest {

    @Mock private TimetableChangeRepository changeRepository;
    @Mock private TimetableRepository timetableRepository;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private AccessControlService accessControlService;
    @InjectMocks private TimetableChangeService service;

    private static final Long ACTOR_USER_ID = 100L;

    @Nested
    @DisplayName("createChange")
    class CreateChange {

        @Test
        @DisplayName("正常系: 臨時変更が作成される")
        void 作成_正常_保存() {
            // Given
            TimetableEntity activeTimetable = TimetableEntity.builder()
                    .teamId(1L).termId(1L).name("テスト")
                    .status(TimetableStatus.ACTIVE)
                    .visibility(TimetableVisibility.MEMBERS_ONLY)
                    .weekPatternEnabled(false).build();
            given(timetableRepository.findById(1L)).willReturn(Optional.of(activeTimetable));
            given(changeRepository.save(any(TimetableChangeEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));

            CreateChangeData data = new CreateChangeData(
                    LocalDate.of(2025, 5, 1), 1, TimetableChangeType.REPLACE,
                    "体育", "佐藤先生", "体育館", "雨天のため", false, false, 100L);

            // When
            TimetableChangeEntity result = service.createChange(1L, data, ACTOR_USER_ID);

            // Then
            assertThat(result.getChangeType()).isEqualTo(TimetableChangeType.REPLACE);
            verify(changeRepository).save(any(TimetableChangeEntity.class));
        }

        @Test
        @DisplayName("異常系: DAY_OFFでperiodNumberが指定されている場合TIMETABLE_031例外")
        void 作成_休日_時限指定_例外() {
            // Given
            TimetableEntity activeTimetable = TimetableEntity.builder()
                    .teamId(1L).termId(1L).name("テスト")
                    .status(TimetableStatus.ACTIVE)
                    .visibility(TimetableVisibility.MEMBERS_ONLY)
                    .weekPatternEnabled(false).build();
            given(timetableRepository.findById(1L)).willReturn(Optional.of(activeTimetable));
            CreateChangeData data = new CreateChangeData(
                    LocalDate.of(2025, 5, 1), 1, TimetableChangeType.DAY_OFF,
                    null, null, null, "祝日", false, false, 100L);

            // When / Then
            assertThatThrownBy(() -> service.createChange(1L, data, ACTOR_USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("TIMETABLE_031"));
        }

        @Test
        @DisplayName("異常系: REPLACEで科目名なしの場合TIMETABLE_033例外")
        void 作成_差替_科目名なし_例外() {
            // Given
            TimetableEntity activeTimetable = TimetableEntity.builder()
                    .teamId(1L).termId(1L).name("テスト")
                    .status(TimetableStatus.ACTIVE)
                    .visibility(TimetableVisibility.MEMBERS_ONLY)
                    .weekPatternEnabled(false).build();
            given(timetableRepository.findById(1L)).willReturn(Optional.of(activeTimetable));
            CreateChangeData data = new CreateChangeData(
                    LocalDate.of(2025, 5, 1), 1, TimetableChangeType.REPLACE,
                    null, null, null, "理由", false, false, 100L);

            // When / Then
            assertThatThrownBy(() -> service.createChange(1L, data, ACTOR_USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("TIMETABLE_033"));
        }
    }

    @Nested
    @DisplayName("updateChange")
    class UpdateChange {

        @Test
        @DisplayName("回帰: updateChangeはfindByIdAndTimetableIdで取得した同一インスタンスをsaveする（toBuilderで新規行を作らない）")
        void 更新_同一インスタンスUPDATE() throws Exception {
            // Given
            TimetableChangeEntity entity = TimetableChangeEntity.builder()
                    .timetableId(1L)
                    .targetDate(LocalDate.of(2025, 5, 1))
                    .periodNumber(1)
                    .changeType(TimetableChangeType.REPLACE)
                    .subjectName("数学")
                    .teacherName("鈴木先生")
                    .notifyMembers(false).build();
            // id を反射でセット
            var idField = entity.getClass().getSuperclass().getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(entity, 30L);

            UpdateChangeData data = new UpdateChangeData(
                    "体育", null, null, null, null);

            TimetableEntity timetable = TimetableEntity.builder()
                    .teamId(1L).termId(1L).name("テスト")
                    .status(TimetableStatus.ACTIVE)
                    .visibility(TimetableVisibility.MEMBERS_ONLY)
                    .weekPatternEnabled(false).build();
            given(timetableRepository.findById(1L)).willReturn(Optional.of(timetable));
            given(changeRepository.findByIdAndTimetableId(30L, 1L)).willReturn(Optional.of(entity));
            given(changeRepository.save(any(TimetableChangeEntity.class))).willAnswer(inv -> inv.getArgument(0));

            // When
            service.updateChange(30L, 1L, data, ACTOR_USER_ID);

            // Then
            // toBuilder().build() で別インスタンスを save していたら id=null の新規行 INSERT になる。
            // 同一インスタンスを save することで UPDATE になっていることを検証する。
            ArgumentCaptor<TimetableChangeEntity> captor = ArgumentCaptor.forClass(TimetableChangeEntity.class);
            verify(changeRepository).save(captor.capture());
            assertThat(captor.getValue()).isSameAs(entity);
            // id が保持されている（= INSERT でなく UPDATE）
            assertThat(captor.getValue().getId()).isEqualTo(30L);
            // subjectName が更新されている
            assertThat(captor.getValue().getSubjectName()).isEqualTo("体育");
        }
    }

    @Nested
    @DisplayName("deleteChange")
    class DeleteChange {

        @Test
        @DisplayName("異常系: 臨時変更不在でTIMETABLE_004例外")
        void 削除_不在_例外() {
            // Given
            TimetableEntity timetable = TimetableEntity.builder()
                    .teamId(1L).termId(1L).name("テスト")
                    .status(TimetableStatus.ACTIVE)
                    .visibility(TimetableVisibility.MEMBERS_ONLY)
                    .weekPatternEnabled(false).build();
            given(timetableRepository.findById(1L)).willReturn(Optional.of(timetable));
            given(changeRepository.findByIdAndTimetableId(1L, 1L)).willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> service.deleteChange(1L, 1L, ACTOR_USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("TIMETABLE_004"));
        }
    }
}
