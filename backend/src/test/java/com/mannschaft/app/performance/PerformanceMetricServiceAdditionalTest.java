package com.mannschaft.app.performance;

import com.mannschaft.app.activity.FieldType;
import com.mannschaft.app.activity.entity.ActivityTemplateFieldEntity;
import com.mannschaft.app.activity.repository.ActivityTemplateFieldRepository;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.performance.dto.MetricResponse;
import com.mannschaft.app.performance.dto.SortOrderRequest;
import com.mannschaft.app.performance.dto.TemplateListResponse;
import com.mannschaft.app.performance.dto.UpdateMetricRequest;
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

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * {@link PerformanceMetricService} の追加単体テスト。
 * listMetrics / updateMetric / updateSortOrder / listTemplates / listLinkableFields を検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PerformanceMetricService 追加単体テスト")
class PerformanceMetricServiceAdditionalTest {

    @Mock
    private PerformanceMetricRepository metricRepository;

    @Mock
    private PerformanceMetricTemplateRepository templateRepository;

    @Mock
    private ActivityTemplateFieldRepository activityTemplateFieldRepository;

    @Mock
    private PerformanceMapper performanceMapper;

    @InjectMocks
    private PerformanceMetricService service;

    private static final Long TEAM_ID = 1L;
    private static final Long METRIC_ID = 100L;

    private PerformanceMetricEntity createMetric(Long id) {
        PerformanceMetricEntity entity = PerformanceMetricEntity.builder()
                .teamId(TEAM_ID)
                .name("テスト指標")
                .unit("km")
                .dataType(MetricDataType.DECIMAL)
                .aggregationType(AggregationType.SUM)
                .build();
        try {
            var idField = entity.getClass().getSuperclass().getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(entity, id);
        } catch (Exception ignored) {}
        return entity;
    }

    // ========================================
    // listMetrics
    // ========================================

    @Nested
    @DisplayName("listMetrics")
    class ListMetrics {

        @Test
        @DisplayName("正常系: チームの指標定義一覧が返る")
        void listMetrics_正常_一覧が返る() {
            // Given
            PerformanceMetricEntity entity = createMetric(METRIC_ID);
            MetricResponse response = new MetricResponse(
                    METRIC_ID, "テスト指標", "km", "DECIMAL", "SUM", null, null, null, null, null,
                    0, true, false, null, true, null, null);

            given(metricRepository.findByTeamIdOrderBySortOrderAsc(TEAM_ID)).willReturn(List.of(entity));
            given(performanceMapper.toMetricResponseList(List.of(entity))).willReturn(List.of(response));

            // When
            List<MetricResponse> result = service.listMetrics(TEAM_ID);

            // Then
            assertThat(result).hasSize(1);
            assertThat(result.get(0).getName()).isEqualTo("テスト指標");
        }

        @Test
        @DisplayName("正常系: 指標なしで空リストが返る")
        void listMetrics_指標なし_空リスト() {
            // Given
            given(metricRepository.findByTeamIdOrderBySortOrderAsc(TEAM_ID)).willReturn(List.of());
            given(performanceMapper.toMetricResponseList(List.of())).willReturn(List.of());

            // When
            List<MetricResponse> result = service.listMetrics(TEAM_ID);

            // Then
            assertThat(result).isEmpty();
        }
    }

    // ========================================
    // updateMetric
    // ========================================

    @Nested
    @DisplayName("updateMetric")
    class UpdateMetric {

        @Test
        @DisplayName("正常系: 指標が更新される")
        void updateMetric_正常_更新される() {
            // Given
            PerformanceMetricEntity entity = createMetric(METRIC_ID);
            UpdateMetricRequest request = new UpdateMetricRequest(
                    "新しい指標名", "m", null, null, null, null, null, null, null, null, null, null, null);
            MetricResponse response = new MetricResponse(
                    METRIC_ID, "新しい指標名", "m", "DECIMAL", "SUM", null, null, null, null, null,
                    0, true, false, null, true, null, null);

            given(metricRepository.findByIdAndTeamId(METRIC_ID, TEAM_ID)).willReturn(Optional.of(entity));
            given(metricRepository.save(any())).willReturn(entity);
            given(performanceMapper.toMetricResponse(any())).willReturn(response);

            // When
            MetricResponse result = service.updateMetric(TEAM_ID, METRIC_ID, request);

            // Then
            assertThat(result).isNotNull();
            verify(metricRepository).save(any());
        }

        @Test
        @DisplayName("異常系: 指標不在でPERF_001例外")
        void updateMetric_不在_例外() {
            // Given
            UpdateMetricRequest request = new UpdateMetricRequest(
                    "新しい指標名", null, null, null, null, null, null, null, null, null, null, null, null);
            given(metricRepository.findByIdAndTeamId(METRIC_ID, TEAM_ID)).willReturn(Optional.empty());

            // When & Then
            assertThatThrownBy(() -> service.updateMetric(TEAM_ID, METRIC_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("PERF_001"));
        }
    }

    // ========================================
    // updateSortOrder
    // ========================================

    @Nested
    @DisplayName("updateSortOrder")
    class UpdateSortOrder {

        @Test
        @DisplayName("正常系: 並び順が更新される")
        void updateSortOrder_正常_更新される() {
            // Given
            PerformanceMetricEntity entity = createMetric(METRIC_ID);
            SortOrderRequest.SortOrderEntry entry = new SortOrderRequest.SortOrderEntry(METRIC_ID, 5);
            SortOrderRequest request = new SortOrderRequest(List.of(entry));

            given(metricRepository.findByIdAndTeamId(METRIC_ID, TEAM_ID)).willReturn(Optional.of(entity));
            given(metricRepository.save(any())).willReturn(entity);

            // When
            var result = service.updateSortOrder(TEAM_ID, request);

            // Then
            assertThat(result.getUpdatedCount()).isEqualTo(1);
            verify(metricRepository).save(any());
        }

        @Test
        @DisplayName("異常系: 指標不在でPERF_001例外")
        void updateSortOrder_不在_例外() {
            // Given
            SortOrderRequest.SortOrderEntry entry = new SortOrderRequest.SortOrderEntry(METRIC_ID, 5);
            SortOrderRequest request = new SortOrderRequest(List.of(entry));

            given(metricRepository.findByIdAndTeamId(METRIC_ID, TEAM_ID)).willReturn(Optional.empty());

            // When & Then
            assertThatThrownBy(() -> service.updateSortOrder(TEAM_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("PERF_001"));
        }
    }

    // ========================================
    // listTemplates
    // ========================================

    @Nested
    @DisplayName("listTemplates")
    class ListTemplates {

        @Test
        @DisplayName("正常系: sportCategoryがnullで全テンプレートが返る")
        void listTemplates_カテゴリnull_全テンプレート() {
            // Given
            PerformanceMetricTemplateEntity template = PerformanceMetricTemplateEntity.builder()
                    .sportCategory("SOCCER")
                    .name("シュート数")
                    .dataType(MetricDataType.INTEGER)
                    .aggregationType(AggregationType.SUM)
                    .sortOrder(1)
                    .isSelfRecordable(false)
                    .build();

            given(templateRepository.findDistinctSportCategories()).willReturn(List.of("SOCCER"));
            given(templateRepository.findAllByOrderBySportCategoryAscSortOrderAsc()).willReturn(List.of(template));
            given(performanceMapper.toTemplateMetric(any())).willReturn(
                    new TemplateListResponse.TemplateMetric(null, "シュート数", null, "INTEGER", "SUM", null, null, null, null, Boolean.FALSE));

            // When
            TemplateListResponse result = service.listTemplates(null);

            // Then
            assertThat(result).isNotNull();
            assertThat(result.getCategories()).contains("SOCCER");
        }

        @Test
        @DisplayName("正常系: sportCategoryを指定してフィルタされる")
        void listTemplates_カテゴリ指定_フィルタ() {
            // Given
            PerformanceMetricTemplateEntity template = PerformanceMetricTemplateEntity.builder()
                    .sportCategory("SOCCER")
                    .name("シュート数")
                    .dataType(MetricDataType.INTEGER)
                    .aggregationType(AggregationType.SUM)
                    .sortOrder(1)
                    .isSelfRecordable(false)
                    .build();

            given(templateRepository.findDistinctSportCategories()).willReturn(List.of("SOCCER", "BASKETBALL"));
            given(templateRepository.findBySportCategoryOrderBySortOrderAsc("SOCCER")).willReturn(List.of(template));
            given(performanceMapper.toTemplateMetric(any())).willReturn(
                    new TemplateListResponse.TemplateMetric(null, "シュート数", null, "INTEGER", "SUM", null, null, null, null, Boolean.FALSE));

            // When
            TemplateListResponse result = service.listTemplates("SOCCER");

            // Then
            assertThat(result).isNotNull();
        }
    }

    // ========================================
    // listLinkableFields
    // ========================================

    @Nested
    @DisplayName("listLinkableFields")
    class ListLinkableFields {

        @Test
        @DisplayName("正常系: 未連携のNUMBERフィールドが返る")
        void listLinkableFields_正常_未連携フィールド返る() {
            // Given
            ActivityTemplateFieldEntity field = ActivityTemplateFieldEntity.builder()
                    .fieldLabel("走行距離")
                    .fieldType(FieldType.NUMBER)
                    .unit("km")
                    .build();
            try {
                var idField = field.getClass().getDeclaredField("id");
                idField.setAccessible(true);
                idField.set(field, 50L);
            } catch (Exception ignored) {}

            given(activityTemplateFieldRepository.findByTeamIdAndFieldType(TEAM_ID, FieldType.NUMBER))
                    .willReturn(List.of(field));
            given(metricRepository.findByTeamIdAndIsActiveTrueOrderBySortOrderAsc(TEAM_ID))
                    .willReturn(List.of());

            // When
            var result = service.listLinkableFields(TEAM_ID);

            // Then
            assertThat(result).hasSize(1);
            assertThat(result.get(0).getFieldName()).isEqualTo("走行距離");
        }

        @Test
        @DisplayName("正常系: 連携済みフィールドは除外される")
        void listLinkableFields_連携済みは除外() {
            // Given
            ActivityTemplateFieldEntity field = ActivityTemplateFieldEntity.builder()
                    .fieldLabel("走行距離")
                    .fieldType(FieldType.NUMBER)
                    .unit("km")
                    .build();
            try {
                var idField = field.getClass().getDeclaredField("id");
                idField.setAccessible(true);
                idField.set(field, 50L);
            } catch (Exception ignored) {}

            PerformanceMetricEntity linkedMetric = PerformanceMetricEntity.builder()
                    .teamId(TEAM_ID)
                    .name("テスト")
                    .dataType(MetricDataType.DECIMAL)
                    .aggregationType(AggregationType.SUM)
                    .linkedActivityFieldId(50L)
                    .build();

            given(activityTemplateFieldRepository.findByTeamIdAndFieldType(TEAM_ID, FieldType.NUMBER))
                    .willReturn(List.of(field));
            given(metricRepository.findByTeamIdAndIsActiveTrueOrderBySortOrderAsc(TEAM_ID))
                    .willReturn(List.of(linkedMetric));

            // When
            var result = service.listLinkableFields(TEAM_ID);

            // Then
            assertThat(result).isEmpty();
        }
    }

    // ========================================
    // getVisibleMetrics
    // ========================================

    @Nested
    @DisplayName("getVisibleMetrics")
    class GetVisibleMetrics {

        @Test
        @DisplayName("正常系: メンバー公開指標が返る")
        void getVisibleMetrics_正常_公開指標返る() {
            // Given
            PerformanceMetricEntity entity = createMetric(METRIC_ID);
            given(metricRepository.findByTeamIdAndIsVisibleToMembersTrueOrderBySortOrderAsc(TEAM_ID))
                    .willReturn(List.of(entity));

            // When
            List<PerformanceMetricEntity> result = service.getVisibleMetrics(TEAM_ID);

            // Then
            assertThat(result).hasSize(1);
        }
    }
}
