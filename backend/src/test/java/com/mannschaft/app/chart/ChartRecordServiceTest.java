package com.mannschaft.app.chart;

import com.mannschaft.app.chart.dto.ChartRecordResponse;
import com.mannschaft.app.chart.dto.CopyChartRequest;
import com.mannschaft.app.chart.dto.CreateChartRecordRequest;
import com.mannschaft.app.chart.dto.PinResponse;
import com.mannschaft.app.chart.dto.ShareResponse;
import com.mannschaft.app.chart.entity.ChartRecordEntity;
import com.mannschaft.app.chart.entity.ChartRecordTemplateEntity;
import com.mannschaft.app.chart.repository.ChartBodyMarkRepository;
import com.mannschaft.app.chart.repository.ChartCustomFieldRepository;
import com.mannschaft.app.chart.repository.ChartCustomValueRepository;
import com.mannschaft.app.chart.repository.ChartFormulaRepository;
import com.mannschaft.app.chart.repository.ChartPhotoRepository;
import com.mannschaft.app.chart.repository.ChartRecordRepository;
import com.mannschaft.app.chart.repository.ChartRecordTemplateRepository;
import com.mannschaft.app.chart.repository.ChartSectionSettingRepository;
import com.mannschaft.app.chart.service.ChartRecordService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.NameResolverService;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("ChartRecordService 単体テスト")
class ChartRecordServiceTest {

    @Mock private ChartRecordRepository recordRepository;
    @Mock private ChartCustomValueRepository customValueRepository;
    @Mock private ChartCustomFieldRepository customFieldRepository;
    @Mock private ChartPhotoRepository photoRepository;
    @Mock private ChartFormulaRepository formulaRepository;
    @Mock private ChartBodyMarkRepository bodyMarkRepository;
    @Mock private ChartSectionSettingRepository sectionSettingRepository;
    @Mock private ChartRecordTemplateRepository recordTemplateRepository;
    @Mock private ChartMapper chartMapper;
    @Mock private ChartPhotoUrlProvider photoUrlProvider;
    @Mock private NameResolverService nameResolverService;

    @InjectMocks
    private ChartRecordService service;

    private static final Long TEAM_ID = 1L;
    private static final Long CHART_ID = 10L;
    private static final Long USER_ID = 100L;

    @Nested
    @DisplayName("getChart")
    class GetChart {
        @Test
        @DisplayName("異常系: カルテ不在でCHART_001例外")
        void 取得_不在_例外() {
            given(recordRepository.findByIdAndTeamId(CHART_ID, TEAM_ID)).willReturn(Optional.empty());
            assertThatThrownBy(() -> service.getChart(TEAM_ID, CHART_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("CHART_001"));
        }
    }

    @Nested
    @DisplayName("createChart")
    class CreateChart {
        @Test
        @DisplayName("正常系: テンプレート適用付きカルテが作成される")
        void 作成_テンプレート適用_保存() {
            CreateChartRecordRequest request = new CreateChartRecordRequest(
                    USER_ID, USER_ID, LocalDate.now(), null, null, null, null, null, null, 5L, null);
            ChartRecordTemplateEntity template = ChartRecordTemplateEntity.builder()
                    .teamId(TEAM_ID).templateName("テスト")
                    .chiefComplaint("テンプレ主訴").treatmentNote("テンプレ施術").build();
            given(recordTemplateRepository.findByIdAndTeamId(5L, TEAM_ID)).willReturn(Optional.of(template));
            ChartRecordEntity saved = ChartRecordEntity.builder()
                    .teamId(TEAM_ID).customerUserId(USER_ID).visitDate(LocalDate.now()).build();
            given(recordRepository.save(any())).willReturn(saved);
            given(sectionSettingRepository.findByTeamId(TEAM_ID)).willReturn(List.of());
            given(customValueRepository.findByChartRecordId(any())).willReturn(List.of());
            given(photoRepository.findByChartRecordIdOrderBySortOrder(any())).willReturn(List.of());
            given(formulaRepository.findByChartRecordIdOrderBySortOrder(any())).willReturn(List.of());
            given(bodyMarkRepository.findByChartRecordId(any())).willReturn(List.of());
            given(chartMapper.toChartRecordResponse(any(), any(), any(), any(), any(), any(), any(), any()))
                    .willReturn(new ChartRecordResponse(null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null));

            ChartRecordResponse result = service.createChart(TEAM_ID, request);
            assertThat(result).isNotNull();
        }
    }

    @Nested
    @DisplayName("deleteChart")
    class DeleteChart {
        @Test
        @DisplayName("正常系: カルテが論理削除される")
        void 削除_正常_論理削除() {
            ChartRecordEntity entity = ChartRecordEntity.builder()
                    .teamId(TEAM_ID).customerUserId(USER_ID).visitDate(LocalDate.now()).build();
            given(recordRepository.findByIdAndTeamId(CHART_ID, TEAM_ID)).willReturn(Optional.of(entity));
            service.deleteChart(TEAM_ID, CHART_ID);
            verify(recordRepository).save(entity);
        }
    }

    @Nested
    @DisplayName("updatePinStatus")
    class UpdatePinStatus {
        @Test
        @DisplayName("異常系: ピン留め上限超過でCHART_016例外")
        void ピン留め_上限超過_例外() {
            ChartRecordEntity entity = ChartRecordEntity.builder()
                    .teamId(TEAM_ID).customerUserId(USER_ID).visitDate(LocalDate.now()).build();
            given(recordRepository.findByIdAndTeamId(CHART_ID, TEAM_ID)).willReturn(Optional.of(entity));
            given(recordRepository.countByTeamIdAndCustomerUserIdAndIsPinnedTrue(TEAM_ID, USER_ID)).willReturn(5L);

            assertThatThrownBy(() -> service.updatePinStatus(TEAM_ID, CHART_ID, true))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("CHART_016"));
        }
    }
}
