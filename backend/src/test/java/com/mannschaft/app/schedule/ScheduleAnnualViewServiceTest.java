package com.mannschaft.app.schedule;

import com.mannschaft.app.schedule.repository.ScheduleEventCategoryRepository;
import com.mannschaft.app.schedule.service.ScheduleAnnualViewService;
import com.mannschaft.app.schedule.service.ScheduleAnnualViewService.YearRange;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ScheduleAnnualViewService} の単体テスト。
 * 年度範囲計算を検証する。
 * （getAnnualView は EntityManager を直接使用するため単体テストでは対象外）
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ScheduleAnnualViewService 単体テスト")
class ScheduleAnnualViewServiceTest {

    @Mock
    private ScheduleEventCategoryRepository categoryRepository;

    @InjectMocks
    private ScheduleAnnualViewService annualViewService;

    // ========================================
    // calculateYearRange
    // ========================================

    @Nested
    @DisplayName("calculateYearRange")
    class CalculateYearRange {

        @Test
        @DisplayName("年度範囲計算_2025年度_4月1日から翌3月31日")
        void 年度範囲計算_2025年度_4月1日から翌3月31日() {
            // when
            YearRange result = annualViewService.calculateYearRange(2025);

            // then
            assertThat(result.start()).isEqualTo(LocalDate.of(2025, 4, 1));
            assertThat(result.end()).isEqualTo(LocalDate.of(2026, 3, 31));
        }

        @Test
        @DisplayName("年度範囲計算_2026年度_正しい範囲")
        void 年度範囲計算_2026年度_正しい範囲() {
            // when
            YearRange result = annualViewService.calculateYearRange(2026);

            // then
            assertThat(result.start()).isEqualTo(LocalDate.of(2026, 4, 1));
            assertThat(result.end()).isEqualTo(LocalDate.of(2027, 3, 31));
        }
    }
}
