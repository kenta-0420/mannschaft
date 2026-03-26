package com.mannschaft.app.chart;

import com.mannschaft.app.chart.dto.BodyMarksResponse;
import com.mannschaft.app.chart.dto.ChartBodyMarkRequest;
import com.mannschaft.app.chart.dto.UpdateBodyMarksRequest;
import com.mannschaft.app.chart.entity.ChartRecordEntity;
import com.mannschaft.app.chart.repository.ChartBodyMarkRepository;
import com.mannschaft.app.chart.repository.ChartRecordRepository;
import com.mannschaft.app.chart.service.ChartBodyMarkService;
import com.mannschaft.app.common.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
@DisplayName("ChartBodyMarkService 単体テスト")
class ChartBodyMarkServiceTest {

    @Mock private ChartBodyMarkRepository bodyMarkRepository;
    @Mock private ChartRecordRepository recordRepository;
    @Mock private ChartMapper chartMapper;

    @InjectMocks
    private ChartBodyMarkService service;

    private static final Long TEAM_ID = 1L;
    private static final Long CHART_ID = 10L;

    @Nested
    @DisplayName("updateBodyMarks")
    class UpdateBodyMarks {
        @Test
        @DisplayName("異常系: カルテ不在でCHART_001例外")
        void 更新_カルテ不在_例外() {
            given(recordRepository.findByIdAndTeamId(CHART_ID, TEAM_ID)).willReturn(Optional.empty());
            assertThatThrownBy(() -> service.updateBodyMarks(TEAM_ID, CHART_ID, new UpdateBodyMarksRequest(List.of())))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("CHART_001"));
        }

        @Test
        @DisplayName("異常系: マーク上限超過でCHART_010例外")
        void 更新_上限超過_例外() {
            ChartRecordEntity record = ChartRecordEntity.builder()
                    .teamId(TEAM_ID).customerUserId(100L).visitDate(LocalDate.now()).build();
            given(recordRepository.findByIdAndTeamId(CHART_ID, TEAM_ID)).willReturn(Optional.of(record));

            List<ChartBodyMarkRequest> marks = new ArrayList<>();
            for (int i = 0; i < 51; i++) {
                marks.add(new ChartBodyMarkRequest("HEAD", BigDecimal.ZERO, BigDecimal.ZERO, "DOT", 1, null));
            }
            UpdateBodyMarksRequest request = new UpdateBodyMarksRequest(marks);

            assertThatThrownBy(() -> service.updateBodyMarks(TEAM_ID, CHART_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("CHART_010"));
        }
    }
}
