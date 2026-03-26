package com.mannschaft.app.timetable;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.timetable.entity.TimetablePeriodTemplateEntity;
import com.mannschaft.app.timetable.repository.TimetablePeriodTemplateRepository;
import com.mannschaft.app.timetable.service.TimetablePeriodTemplateService;
import com.mannschaft.app.timetable.service.TimetablePeriodTemplateService.PeriodTemplateData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("TimetablePeriodTemplateService 単体テスト")
class TimetablePeriodTemplateServiceTest {

    @Mock private TimetablePeriodTemplateRepository periodTemplateRepository;
    @InjectMocks private TimetablePeriodTemplateService service;

    @Nested
    @DisplayName("replaceAll")
    class ReplaceAll {

        @Test
        @DisplayName("正常系: 時限テンプレートが全置換される")
        void 置換_正常_保存() {
            // Given
            List<PeriodTemplateData> periods = List.of(
                    new PeriodTemplateData(1, "1時限", LocalTime.of(8, 45), LocalTime.of(9, 35), false),
                    new PeriodTemplateData(2, "2時限", LocalTime.of(9, 45), LocalTime.of(10, 35), false));
            given(periodTemplateRepository.saveAll(anyList())).willAnswer(inv -> inv.getArgument(0));

            // When
            List<TimetablePeriodTemplateEntity> result = service.replaceAll(1L, periods);

            // Then
            assertThat(result).hasSize(2);
            verify(periodTemplateRepository).deleteByOrganizationId(1L);
        }

        @Test
        @DisplayName("異常系: 15件超過でTIMETABLE_031例外")
        void 置換_上限超過_例外() {
            // Given
            List<PeriodTemplateData> periods = new ArrayList<>();
            for (int i = 1; i <= 16; i++) {
                periods.add(new PeriodTemplateData(i, i + "時限",
                        LocalTime.of(8, 0).plusMinutes(i * 50),
                        LocalTime.of(8, 45).plusMinutes(i * 50), false));
            }

            // When / Then
            assertThatThrownBy(() -> service.replaceAll(1L, periods))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("TIMETABLE_031"));
        }

        @Test
        @DisplayName("異常系: period_number重複でTIMETABLE_031例外")
        void 置換_番号重複_例外() {
            // Given
            List<PeriodTemplateData> periods = List.of(
                    new PeriodTemplateData(1, "1時限", LocalTime.of(8, 45), LocalTime.of(9, 35), false),
                    new PeriodTemplateData(1, "1時限再", LocalTime.of(9, 45), LocalTime.of(10, 35), false));

            // When / Then
            assertThatThrownBy(() -> service.replaceAll(1L, periods))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("TIMETABLE_031"));
        }
    }
}
