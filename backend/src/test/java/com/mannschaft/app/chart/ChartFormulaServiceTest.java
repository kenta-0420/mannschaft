package com.mannschaft.app.chart;

import com.mannschaft.app.chart.dto.CreateFormulaRequest;
import com.mannschaft.app.chart.entity.ChartRecordEntity;
import com.mannschaft.app.chart.repository.ChartFormulaRepository;
import com.mannschaft.app.chart.repository.ChartRecordRepository;
import com.mannschaft.app.chart.service.ChartFormulaService;
import com.mannschaft.app.common.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
@DisplayName("ChartFormulaService 単体テスト")
class ChartFormulaServiceTest {

    @Mock private ChartFormulaRepository formulaRepository;
    @Mock private ChartRecordRepository recordRepository;
    @Mock private ChartMapper chartMapper;

    @InjectMocks
    private ChartFormulaService service;

    private static final Long TEAM_ID = 1L;
    private static final Long CHART_ID = 10L;
    private static final Long FORMULA_ID = 20L;

    @Nested
    @DisplayName("createFormula")
    class CreateFormula {
        @Test
        @DisplayName("異常系: カルテ不在でCHART_001例外")
        void 作成_カルテ不在_例外() {
            given(recordRepository.findByIdAndTeamId(CHART_ID, TEAM_ID)).willReturn(Optional.empty());
            assertThatThrownBy(() -> service.createFormula(TEAM_ID, CHART_ID,
                    new CreateFormulaRequest("テスト", null, null, null, null, null, null, null)))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("CHART_001"));
        }

        @Test
        @DisplayName("異常系: レシピ上限超過でCHART_011例外")
        void 作成_上限超過_例外() {
            ChartRecordEntity record = ChartRecordEntity.builder()
                    .teamId(TEAM_ID).customerUserId(100L).visitDate(LocalDate.now()).build();
            given(recordRepository.findByIdAndTeamId(CHART_ID, TEAM_ID)).willReturn(Optional.of(record));
            given(formulaRepository.countByChartRecordId(CHART_ID)).willReturn(20L);

            assertThatThrownBy(() -> service.createFormula(TEAM_ID, CHART_ID,
                    new CreateFormulaRequest("テスト", null, null, null, null, null, null, null)))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("CHART_011"));
        }
    }

    @Nested
    @DisplayName("deleteFormula")
    class DeleteFormula {
        @Test
        @DisplayName("異常系: レシピ不在でCHART_003例外")
        void 削除_不在_例外() {
            given(formulaRepository.findById(FORMULA_ID)).willReturn(Optional.empty());
            assertThatThrownBy(() -> service.deleteFormula(TEAM_ID, FORMULA_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("CHART_003"));
        }
    }
}
