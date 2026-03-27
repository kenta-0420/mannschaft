package com.mannschaft.app.performance;

import com.mannschaft.app.activity.FieldType;
import com.mannschaft.app.activity.entity.ActivityTemplateFieldEntity;
import com.mannschaft.app.activity.repository.ActivityTemplateFieldRepository;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.performance.dto.CreateMetricRequest;
import com.mannschaft.app.performance.dto.FromTemplateRequest;
import com.mannschaft.app.performance.dto.FromTemplateResponse;
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

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link PerformanceMetricService} のカバレッジ補完テスト。
 * createFromTemplate 正常系・制限超過、deactivateMetric 正常系、
 * createMetric の各 DataType/AggregationType パスを検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PerformanceMetricService カバレッジ補完テスト")
class PerformanceMetricServiceCoverageTest {

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

    private PerformanceMetricEntity createMetricEntity(Long id) {
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

    private PerformanceMetricTemplateEntity createTemplate(String name, String sportCategory) {
        return PerformanceMetricTemplateEntity.builder()
                .sportCategory(sportCategory)
                .name(name)
                .unit("個")
                .dataType(MetricDataType.INTEGER)
                .aggregationType(AggregationType.SUM)
                .sortOrder(1)
                .isSelfRecordable(false)
                .build();
    }

    // ========================================
    // createFromTemplate - 正常系
    // ========================================

    @Nested
    @DisplayName("createFromTemplate - 正常系")
    class CreateFromTemplateSuccess {

        @Test
        @DisplayName("正常系: テンプレートから指標が一括作成される")
        void テンプレートから指標が一括作成される() {
            // Given
            PerformanceMetricTemplateEntity t1 = createTemplate("シュート数", "SOCCER");
            PerformanceMetricTemplateEntity t2 = createTemplate("ドリブル成功数", "SOCCER");
            FromTemplateRequest request = new FromTemplateRequest("SOCCER", null);

            given(templateRepository.findBySportCategoryOrderBySortOrderAsc("SOCCER"))
                    .willReturn(List.of(t1, t2));
            given(metricRepository.findActiveNamesByTeamId(TEAM_ID)).willReturn(List.of());
            given(metricRepository.countByTeamIdAndIsActiveTrue(TEAM_ID)).willReturn(0L);
            given(metricRepository.save(any(PerformanceMetricEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));
            given(performanceMapper.toMetricResponseList(any())).willReturn(List.of());

            // When
            FromTemplateResponse result = service.createFromTemplate(TEAM_ID, request);

            // Then
            assertThat(result.getCreatedCount()).isEqualTo(2);
            verify(metricRepository, org.mockito.Mockito.times(2)).save(any(PerformanceMetricEntity.class));
        }

        @Test
        @DisplayName("正常系: excludeNamesで除外されたテンプレートはスキップされる")
        void excludeNamesで指定された指標はスキップ() {
            // Given
            PerformanceMetricTemplateEntity t1 = createTemplate("シュート数", "SOCCER");
            PerformanceMetricTemplateEntity t2 = createTemplate("ドリブル成功数", "SOCCER");
            FromTemplateRequest request = new FromTemplateRequest("SOCCER", List.of("シュート数"));

            given(templateRepository.findBySportCategoryOrderBySortOrderAsc("SOCCER"))
                    .willReturn(List.of(t1, t2));
            given(metricRepository.findActiveNamesByTeamId(TEAM_ID)).willReturn(List.of());
            given(metricRepository.countByTeamIdAndIsActiveTrue(TEAM_ID)).willReturn(0L);
            given(metricRepository.save(any(PerformanceMetricEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));
            given(performanceMapper.toMetricResponseList(any())).willReturn(List.of());

            // When
            FromTemplateResponse result = service.createFromTemplate(TEAM_ID, request);

            // Then
            assertThat(result.getCreatedCount()).isEqualTo(1);
            verify(metricRepository, org.mockito.Mockito.times(1)).save(any(PerformanceMetricEntity.class));
        }

        @Test
        @DisplayName("正常系: 既存指標名と同じテンプレートはスキップされる")
        void 既存指標名と同じテンプレートはスキップ() {
            // Given
            PerformanceMetricTemplateEntity t1 = createTemplate("シュート数", "SOCCER");
            PerformanceMetricTemplateEntity t2 = createTemplate("ドリブル成功数", "SOCCER");
            FromTemplateRequest request = new FromTemplateRequest("SOCCER", null);

            given(templateRepository.findBySportCategoryOrderBySortOrderAsc("SOCCER"))
                    .willReturn(List.of(t1, t2));
            given(metricRepository.findActiveNamesByTeamId(TEAM_ID)).willReturn(List.of("シュート数"));
            given(metricRepository.countByTeamIdAndIsActiveTrue(TEAM_ID)).willReturn(1L);
            given(metricRepository.save(any(PerformanceMetricEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));
            given(performanceMapper.toMetricResponseList(any())).willReturn(List.of());

            // When
            FromTemplateResponse result = service.createFromTemplate(TEAM_ID, request);

            // Then
            assertThat(result.getCreatedCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("異常系: テンプレート適用後に上限を超えるとPERF_003例外")
        void テンプレート適用後に上限超過するとエラー() {
            // Given
            PerformanceMetricTemplateEntity t1 = createTemplate("シュート数", "SOCCER");
            PerformanceMetricTemplateEntity t2 = createTemplate("ドリブル成功数", "SOCCER");
            FromTemplateRequest request = new FromTemplateRequest("SOCCER", null);

            given(templateRepository.findBySportCategoryOrderBySortOrderAsc("SOCCER"))
                    .willReturn(List.of(t1, t2));
            given(metricRepository.findActiveNamesByTeamId(TEAM_ID)).willReturn(List.of());
            // 現在29件 + 2件 = 31件 > 30件上限
            given(metricRepository.countByTeamIdAndIsActiveTrue(TEAM_ID)).willReturn(29L);

            // When & Then
            assertThatThrownBy(() -> service.createFromTemplate(TEAM_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("PERF_003"));
            verify(metricRepository, never()).save(any());
        }
    }

    // ========================================
    // deactivateMetric - 正常系
    // ========================================

    @Nested
    @DisplayName("deactivateMetric - 正常系")
    class DeactivateMetricSuccess {

        @Test
        @DisplayName("正常系: 指標が無効化される")
        void 指標が無効化される() {
            // Given
            PerformanceMetricEntity entity = createMetricEntity(METRIC_ID);
            given(metricRepository.findByIdAndTeamId(METRIC_ID, TEAM_ID)).willReturn(Optional.of(entity));
            given(metricRepository.save(any(PerformanceMetricEntity.class))).willReturn(entity);

            // When
            service.deactivateMetric(TEAM_ID, METRIC_ID);

            // Then
            assertThat(entity.getIsActive()).isFalse();
            verify(metricRepository).save(entity);
        }
    }

    // ========================================
    // createMetric - 各 DataType/AggregationType
    // ========================================

    @Nested
    @DisplayName("createMetric - DataType/AggregationType バリエーション")
    class CreateMetricVariants {

        @Test
        @DisplayName("正常系: INTEGER/AVGで指標が作成される")
        void INTEGER_AVG_で指標が作成される() {
            // Given
            given(metricRepository.countByTeamIdAndIsActiveTrue(TEAM_ID)).willReturn(0L);
            CreateMetricRequest request = new CreateMetricRequest(
                    "ゴール数", "本", "INTEGER", "AVG",
                    "説明", "得点", new BigDecimal("10"), BigDecimal.ZERO, new BigDecimal("100"),
                    1, false, true, null);

            PerformanceMetricEntity saved = PerformanceMetricEntity.builder()
                    .teamId(TEAM_ID).name("ゴール数").unit("本")
                    .dataType(MetricDataType.INTEGER).aggregationType(AggregationType.AVG)
                    .build();
            given(metricRepository.save(any())).willReturn(saved);
            given(performanceMapper.toMetricResponse(saved)).willReturn(
                    new MetricResponse(1L, "ゴール数", "本", "INTEGER", "AVG",
                            null, null, null, null, null,
                            1, false, true, null, true, null, null));

            // When
            MetricResponse result = service.createMetric(TEAM_ID, request);

            // Then
            assertThat(result).isNotNull();
            assertThat(result.getAggregationType()).isEqualTo("AVG");
        }

        @Test
        @DisplayName("正常系: MAX集計で指標が作成される")
        void MAX集計で指標が作成される() {
            // Given
            given(metricRepository.countByTeamIdAndIsActiveTrue(TEAM_ID)).willReturn(0L);
            CreateMetricRequest request = new CreateMetricRequest(
                    "最高速度", "km/h", "DECIMAL", "MAX",
                    null, null, null, null, null, null, true, false, null);

            PerformanceMetricEntity saved = PerformanceMetricEntity.builder()
                    .teamId(TEAM_ID).name("最高速度").unit("km/h")
                    .dataType(MetricDataType.DECIMAL).aggregationType(AggregationType.MAX)
                    .build();
            given(metricRepository.save(any())).willReturn(saved);
            given(performanceMapper.toMetricResponse(saved)).willReturn(
                    new MetricResponse(2L, "最高速度", "km/h", "DECIMAL", "MAX",
                            null, null, null, null, null,
                            0, true, false, null, true, null, null));

            // When
            MetricResponse result = service.createMetric(TEAM_ID, request);

            // Then
            assertThat(result).isNotNull();
        }

        @Test
        @DisplayName("正常系: MIN集計で指標が作成される")
        void MIN集計で指標が作成される() {
            // Given
            given(metricRepository.countByTeamIdAndIsActiveTrue(TEAM_ID)).willReturn(0L);
            CreateMetricRequest request = new CreateMetricRequest(
                    "最低タイム", "秒", "DECIMAL", "MIN",
                    null, null, null, null, null, null, null, null, null);

            PerformanceMetricEntity saved = PerformanceMetricEntity.builder()
                    .teamId(TEAM_ID).name("最低タイム").unit("秒")
                    .dataType(MetricDataType.DECIMAL).aggregationType(AggregationType.MIN)
                    .build();
            given(metricRepository.save(any())).willReturn(saved);
            given(performanceMapper.toMetricResponse(saved)).willReturn(
                    new MetricResponse(3L, "最低タイム", "秒", "DECIMAL", "MIN",
                            null, null, null, null, null,
                            0, true, false, null, true, null, null));

            // When
            MetricResponse result = service.createMetric(TEAM_ID, request);

            // Then
            assertThat(result).isNotNull();
        }

        @Test
        @DisplayName("正常系: LATEST集計で指標が作成される")
        void LATEST集計で指標が作成される() {
            // Given
            given(metricRepository.countByTeamIdAndIsActiveTrue(TEAM_ID)).willReturn(0L);
            CreateMetricRequest request = new CreateMetricRequest(
                    "体重", "kg", "DECIMAL", "LATEST",
                    null, null, null, null, null, null, null, null, null);

            PerformanceMetricEntity saved = PerformanceMetricEntity.builder()
                    .teamId(TEAM_ID).name("体重").unit("kg")
                    .dataType(MetricDataType.DECIMAL).aggregationType(AggregationType.LATEST)
                    .build();
            given(metricRepository.save(any())).willReturn(saved);
            given(performanceMapper.toMetricResponse(saved)).willReturn(
                    new MetricResponse(4L, "体重", "kg", "DECIMAL", "LATEST",
                            null, null, null, null, null,
                            0, true, false, null, true, null, null));

            // When
            MetricResponse result = service.createMetric(TEAM_ID, request);

            // Then
            assertThat(result).isNotNull();
        }

        @Test
        @DisplayName("正常系: sortOrder/isVisibleToMembers/isSelfRecordable指定で作成される")
        void フラグ明示指定で指標が作成される() {
            // Given
            given(metricRepository.countByTeamIdAndIsActiveTrue(TEAM_ID)).willReturn(5L);
            CreateMetricRequest request = new CreateMetricRequest(
                    "アシスト数", "本", null, null,
                    null, null, null, null, null, 3, false, true, 999L);

            PerformanceMetricEntity saved = PerformanceMetricEntity.builder()
                    .teamId(TEAM_ID).name("アシスト数").unit("本")
                    .dataType(MetricDataType.DECIMAL).aggregationType(AggregationType.SUM)
                    .sortOrder(3).isVisibleToMembers(false).isSelfRecordable(true)
                    .linkedActivityFieldId(999L)
                    .build();
            given(metricRepository.save(any())).willReturn(saved);
            given(performanceMapper.toMetricResponse(saved)).willReturn(
                    new MetricResponse(5L, "アシスト数", "本", "DECIMAL", "SUM",
                            null, null, null, null, null,
                            3, false, true, 999L, true, null, null));

            // When
            MetricResponse result = service.createMetric(TEAM_ID, request);

            // Then
            assertThat(result).isNotNull();
            assertThat(result.getSortOrder()).isEqualTo(3);
        }
    }

    // ========================================
    // getActiveMetrics / getMetricEntity
    // ========================================

    @Nested
    @DisplayName("getActiveMetrics / getMetricEntity")
    class InternalMethods {

        @Test
        @DisplayName("正常系: getActiveMetrics がアクティブ指標一覧を返す")
        void getActiveMetrics_アクティブ指標一覧が返る() {
            // Given
            PerformanceMetricEntity entity = createMetricEntity(METRIC_ID);
            given(metricRepository.findByTeamIdAndIsActiveTrueOrderBySortOrderAsc(TEAM_ID))
                    .willReturn(List.of(entity));

            // When
            List<PerformanceMetricEntity> result = service.getActiveMetrics(TEAM_ID);

            // Then
            assertThat(result).hasSize(1);
        }

        @Test
        @DisplayName("正常系: getMetricEntity が指標エンティティを返す")
        void getMetricEntity_指標エンティティが返る() {
            // Given
            PerformanceMetricEntity entity = createMetricEntity(METRIC_ID);
            given(metricRepository.findByIdAndTeamId(METRIC_ID, TEAM_ID)).willReturn(Optional.of(entity));

            // When
            PerformanceMetricEntity result = service.getMetricEntity(TEAM_ID, METRIC_ID);

            // Then
            assertThat(result).isNotNull();
            assertThat(result.getName()).isEqualTo("テスト指標");
        }

        @Test
        @DisplayName("異常系: getMetricEntity で指標不在ならPERF_001例外")
        void getMetricEntity_不在_例外() {
            // Given
            given(metricRepository.findByIdAndTeamId(METRIC_ID, TEAM_ID)).willReturn(Optional.empty());

            // When & Then
            assertThatThrownBy(() -> service.getMetricEntity(TEAM_ID, METRIC_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("PERF_001"));
        }
    }

    // ========================================
    // listLinkableFields - linkedActivityFieldId が null のケース
    // ========================================

    @Nested
    @DisplayName("listLinkableFields - linkedActivityFieldId null の指標がある場合")
    class ListLinkableFieldsNullLinkedId {

        @Test
        @DisplayName("正常系: linkedActivityFieldIdがnullの指標があっても除外されない")
        void linkedActivityFieldIdがnullでも正常に動作する() {
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

            // linkedActivityFieldId が null のメトリック
            PerformanceMetricEntity nullLinkedMetric = PerformanceMetricEntity.builder()
                    .teamId(TEAM_ID)
                    .name("テスト")
                    .dataType(MetricDataType.DECIMAL)
                    .aggregationType(AggregationType.SUM)
                    .linkedActivityFieldId(null)
                    .build();

            given(activityTemplateFieldRepository.findByTeamIdAndFieldType(TEAM_ID, FieldType.NUMBER))
                    .willReturn(List.of(field));
            given(metricRepository.findByTeamIdAndIsActiveTrueOrderBySortOrderAsc(TEAM_ID))
                    .willReturn(List.of(nullLinkedMetric));

            // When
            var result = service.listLinkableFields(TEAM_ID);

            // Then
            assertThat(result).hasSize(1); // null のフィールドはスキップされ、フィールド50Lは返却される
        }
    }
}
