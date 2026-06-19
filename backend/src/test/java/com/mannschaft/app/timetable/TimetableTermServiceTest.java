package com.mannschaft.app.timetable;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.timetable.entity.TimetableTermEntity;
import com.mannschaft.app.timetable.repository.TimetableRepository;
import com.mannschaft.app.timetable.repository.TimetableTermRepository;
import com.mannschaft.app.timetable.service.TimetableTermService;
import com.mannschaft.app.timetable.service.TimetableTermService.CreateTermData;
import com.mannschaft.app.timetable.service.TimetableTermService.UpdateTermData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("TimetableTermService 単体テスト")
class TimetableTermServiceTest {

    @Mock private TimetableTermRepository termRepository;
    @Mock private TimetableRepository timetableRepository;
    @InjectMocks private TimetableTermService service;

    @Nested
    @DisplayName("createTerm")
    class CreateTerm {

        @Test
        @DisplayName("正常系: 学期が作成される")
        void 作成_正常_保存() {
            // Given
            given(termRepository.findByTeamIdAndAcademicYearOrderBySortOrder(1L, 2025))
                    .willReturn(List.of());
            given(termRepository.save(any(TimetableTermEntity.class))).willAnswer(inv -> inv.getArgument(0));

            CreateTermData data = new CreateTermData(2025, "1学期",
                    LocalDate.of(2025, 4, 1), LocalDate.of(2025, 7, 31), 1);

            // When
            TimetableTermEntity result = service.createTerm(1L, true, data);

            // Then
            assertThat(result.getName()).isEqualTo("1学期");
            verify(termRepository).save(any(TimetableTermEntity.class));
        }

        @Test
        @DisplayName("異常系: 同名学期が存在する場合TIMETABLE_020例外")
        void 作成_名前重複_例外() {
            // Given
            TimetableTermEntity existing = TimetableTermEntity.builder()
                    .teamId(1L).academicYear(2025).name("1学期")
                    .startDate(LocalDate.of(2025, 4, 1))
                    .endDate(LocalDate.of(2025, 7, 31)).sortOrder(1).build();
            // idを設定（excludeTermIdのチェック用）
            try {
                var field = existing.getClass().getSuperclass().getDeclaredField("id");
                field.setAccessible(true);
                field.set(existing, 99L);
            } catch (Exception ignored) {}
            given(termRepository.findByTeamIdAndAcademicYearOrderBySortOrder(1L, 2025))
                    .willReturn(List.of(existing));

            CreateTermData data = new CreateTermData(2025, "1学期",
                    LocalDate.of(2025, 10, 1), LocalDate.of(2025, 12, 31), 2);

            // When / Then
            assertThatThrownBy(() -> service.createTerm(1L, true, data))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("TIMETABLE_020"));
        }
    }

    @Nested
    @DisplayName("updateTerm")
    class UpdateTerm {

        @Test
        @DisplayName("回帰: updateTermはfindByIdで取得した同一インスタンスをsaveする（toBuilderで新規行を作らない）")
        void 更新_同一インスタンスUPDATE() throws Exception {
            // Given
            TimetableTermEntity entity = TimetableTermEntity.builder()
                    .teamId(1L).organizationId(null).academicYear(2025)
                    .name("1学期")
                    .startDate(LocalDate.of(2025, 4, 1))
                    .endDate(LocalDate.of(2025, 7, 31))
                    .sortOrder(1).build();
            // id を反射でセット（BaseEntity の GeneratedValue フィールド）
            var idField = entity.getClass().getSuperclass().getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(entity, 10L);

            UpdateTermData data = new UpdateTermData(
                    "前期", LocalDate.of(2025, 4, 1), LocalDate.of(2025, 7, 31), 1);

            given(termRepository.findById(10L)).willReturn(Optional.of(entity));
            // 重複チェックは空リストで通過
            given(termRepository.findByTeamIdAndAcademicYearOrderBySortOrder(1L, 2025))
                    .willReturn(List.of(entity));
            given(termRepository.save(any(TimetableTermEntity.class))).willAnswer(inv -> inv.getArgument(0));

            // When
            service.updateTerm(10L, data);

            // Then
            // toBuilder().build() で別インスタンスを save していたら id=null の新規行 INSERT になる。
            // 同一インスタンスを save することで UPDATE になっていることを検証する。
            ArgumentCaptor<TimetableTermEntity> captor = ArgumentCaptor.forClass(TimetableTermEntity.class);
            verify(termRepository).save(captor.capture());
            assertThat(captor.getValue()).isSameAs(entity);
            // id が保持されている（= INSERT でなく UPDATE）
            assertThat(captor.getValue().getId()).isEqualTo(10L);
            // フィールドが更新されている
            assertThat(captor.getValue().getName()).isEqualTo("前期");
        }
    }

    @Nested
    @DisplayName("getByTermId")
    class GetByTermId {

        @Test
        @DisplayName("異常系: 学期不在でTIMETABLE_002例外")
        void 取得_不在_例外() {
            // Given
            given(termRepository.findById(1L)).willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> service.getByTermId(1L))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("TIMETABLE_002"));
        }
    }
}
