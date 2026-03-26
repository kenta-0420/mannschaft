package com.mannschaft.app.performance;

import com.mannschaft.app.activity.FieldType;
import com.mannschaft.app.activity.repository.ActivityTemplateFieldRepository;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.performance.dto.CreateMetricRequest;
import com.mannschaft.app.performance.dto.FromTemplateRequest;
import com.mannschaft.app.performance.dto.MetricResponse;
import com.mannschaft.app.performance.entity.PerformanceMetricEntity;
import com.mannschaft.app.performance.entity.PerformanceMetricTemplateEntity;
import com.mannschaft.app.performance.repository.PerformanceMetricRepository;
import com.mannschaft.app.performance.repository.PerformanceMetricTemplateRepository;
import com.mannschaft.app.performance.service.PerformanceMetricService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
@DisplayName("PerformanceMetricService 単体テスト")
class PerformanceMetricServiceTest {

    @Mock private PerformanceMetricRepository metricRepository;
    @Mock private PerformanceMetricTemplateRepository templateRepository;
    @Mock private ActivityTemplateFieldRepository activityTemplateFieldRepository;
    @Mock private PerformanceMapper performanceMapper;

    @InjectMocks
    private PerformanceMetricService service;

    private static final Long TEAM_ID = 1L;

    @Nested
    @DisplayName("createMetric")
    class CreateMetric {
        @Test
        @DisplayName("異常系: 指標上限超過でPERF_003例外")
        void 作成_上限超過_例外() {
            given(metricRepository.countByTeamIdAndIsActiveTrue(TEAM_ID)).willReturn(30L);
            CreateMetricRequest request = new CreateMetricRequest(
                    "新指標", null, null, null, null, null, null, null, null, null, null, null, null);

            assertThatThrownBy(() -> service.createMetric(TEAM_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("PERF_003"));
        }

        @Test
        @DisplayName("正常系: 指標が作成される")
        void 作成_正常_保存() {
            given(metricRepository.countByTeamIdAndIsActiveTrue(TEAM_ID)).willReturn(0L);
            CreateMetricRequest request = new CreateMetricRequest(
                    "50m走", "秒", null, null, null, null, null, null, null, null, null, null, null);
            PerformanceMetricEntity saved = PerformanceMetricEntity.builder()
                    .teamId(TEAM_ID).name("50m走").unit("秒").dataType(MetricDataType.DECIMAL)
                    .aggregationType(AggregationType.SUM).build();
            given(metricRepository.save(any())).willReturn(saved);
            given(performanceMapper.toMetricResponse(saved)).willReturn(new MetricResponse(
                    1L, "50m走", "秒", "DECIMAL", "SUM", null, null, null, null, null,
                    0, true, false, null, true, null, null));

            MetricResponse result = service.createMetric(TEAM_ID, request);
            assertThat(result).isNotNull();
        }
    }

    @Nested
    @DisplayName("deactivateMetric")
    class DeactivateMetric {
        @Test
        @DisplayName("異常系: 指標不在でPERF_001例外")
        void 無効化_不在_例外() {
            given(metricRepository.findByIdAndTeamId(99L, TEAM_ID)).willReturn(Optional.empty());
            assertThatThrownBy(() -> service.deactivateMetric(TEAM_ID, 99L))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("PERF_001"));
        }
    }

    @Nested
    @DisplayName("createFromTemplate")
    class CreateFromTemplate {
        @Test
        @DisplayName("異常系: テンプレート不在でPERF_010例外")
        void テンプレート_不在_例外() {
            given(templateRepository.findBySportCategoryOrderBySortOrderAsc("UNKNOWN")).willReturn(List.of());
            FromTemplateRequest request = new FromTemplateRequest("UNKNOWN", null);

            assertThatThrownBy(() -> service.createFromTemplate(TEAM_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("PERF_010"));
        }
    }
}
