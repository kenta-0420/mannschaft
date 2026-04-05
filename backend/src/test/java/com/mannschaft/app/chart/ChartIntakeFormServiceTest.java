package com.mannschaft.app.chart;

import com.mannschaft.app.chart.dto.IntakeFormResponse;
import com.mannschaft.app.chart.dto.UpdateIntakeFormRequest;
import com.mannschaft.app.chart.entity.ChartIntakeFormEntity;
import com.mannschaft.app.chart.entity.ChartRecordEntity;
import com.mannschaft.app.chart.repository.ChartIntakeFormRepository;
import com.mannschaft.app.chart.repository.ChartRecordRepository;
import com.mannschaft.app.chart.service.ChartIntakeFormService;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
@DisplayName("ChartIntakeFormService 単体テスト")
class ChartIntakeFormServiceTest {

    @Mock private ChartIntakeFormRepository intakeFormRepository;
    @Mock private ChartRecordRepository recordRepository;
    @Mock private ChartMapper chartMapper;

    @InjectMocks
    private ChartIntakeFormService service;

    private static final Long TEAM_ID = 1L;
    private static final Long CHART_ID = 10L;

    @Nested
    @DisplayName("getIntakeForms")
    class GetIntakeForms {
        @Test
        @DisplayName("異常系: カルテ不在でCHART_001例外")
        void 取得_カルテ不在_例外() {
            given(recordRepository.findByIdAndTeamId(CHART_ID, TEAM_ID)).willReturn(Optional.empty());
            assertThatThrownBy(() -> service.getIntakeForms(TEAM_ID, CHART_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("CHART_001"));
        }

        @Test
        @DisplayName("正常系: 問診票一覧が返却される")
        void 取得_正常_返却() {
            ChartRecordEntity record = ChartRecordEntity.builder()
                    .teamId(TEAM_ID).customerUserId(100L).visitDate(LocalDate.now()).build();
            given(recordRepository.findByIdAndTeamId(CHART_ID, TEAM_ID)).willReturn(Optional.of(record));
            given(intakeFormRepository.findByChartRecordId(CHART_ID)).willReturn(List.of());
            given(chartMapper.toIntakeFormResponseList(any())).willReturn(List.of());

            List<IntakeFormResponse> result = service.getIntakeForms(TEAM_ID, CHART_ID);
            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("updateIntakeForm")
    class UpdateIntakeForm {
        @Test
        @DisplayName("正常系: 新規問診票が作成される")
        void 更新_新規_作成() {
            ChartRecordEntity record = ChartRecordEntity.builder()
                    .teamId(TEAM_ID).customerUserId(100L).visitDate(LocalDate.now()).build();
            given(recordRepository.findByIdAndTeamId(CHART_ID, TEAM_ID)).willReturn(Optional.of(record));
            given(intakeFormRepository.findByChartRecordIdAndFormType(CHART_ID, "GENERAL"))
                    .willReturn(Optional.empty());
            ChartIntakeFormEntity saved = ChartIntakeFormEntity.builder()
                    .chartRecordId(CHART_ID).formType("GENERAL").content("内容").build();
            given(intakeFormRepository.save(any())).willReturn(saved);
            given(chartMapper.toIntakeFormResponse(saved)).willReturn(
                    new IntakeFormResponse(null, null, null, null, null, null, null, null, null));

            UpdateIntakeFormRequest request = new UpdateIntakeFormRequest("GENERAL", "問診内容", null, null);
            IntakeFormResponse result = service.updateIntakeForm(TEAM_ID, CHART_ID, request);
            assertThat(result).isNotNull();
        }
    }
}
