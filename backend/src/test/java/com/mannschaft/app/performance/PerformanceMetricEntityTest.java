package com.mannschaft.app.performance;

import com.mannschaft.app.performance.entity.PerformanceMetricEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link PerformanceMetricEntity} の単体テスト。
 * update / deactivate / updateSortOrder / markTargetAchievedNotified を検証する。
 */
@DisplayName("PerformanceMetricEntity 単体テスト")
class PerformanceMetricEntityTest {

    private PerformanceMetricEntity createEntity() {
        return PerformanceMetricEntity.builder()
                .teamId(1L)
                .name("テスト指標")
                .unit("km")
                .dataType(MetricDataType.DECIMAL)
                .aggregationType(AggregationType.SUM)
                .targetValue(new BigDecimal("100"))
                .sortOrder(0)
                .isVisibleToMembers(true)
                .isSelfRecordable(false)
                .build();
    }

    @Nested
    @DisplayName("update")
    class Update {

        @Test
        @DisplayName("正常系: 全フィールドが更新される")
        void 全フィールドが更新される() {
            // Given
            PerformanceMetricEntity entity = createEntity();

            // When
            entity.update(
                    "新しい指標名", "m",
                    MetricDataType.INTEGER, AggregationType.AVG,
                    "新しい説明", "新グループ",
                    new BigDecimal("200"),
                    new BigDecimal("10"), new BigDecimal("500"),
                    5, false, true, 999L);

            // Then
            assertThat(entity.getName()).isEqualTo("新しい指標名");
            assertThat(entity.getUnit()).isEqualTo("m");
            assertThat(entity.getDataType()).isEqualTo(MetricDataType.INTEGER);
            assertThat(entity.getAggregationType()).isEqualTo(AggregationType.AVG);
            assertThat(entity.getDescription()).isEqualTo("新しい説明");
            assertThat(entity.getGroupName()).isEqualTo("新グループ");
            assertThat(entity.getTargetValue()).isEqualByComparingTo(new BigDecimal("200"));
            assertThat(entity.getMinValue()).isEqualByComparingTo(new BigDecimal("10"));
            assertThat(entity.getMaxValue()).isEqualByComparingTo(new BigDecimal("500"));
            assertThat(entity.getSortOrder()).isEqualTo(5);
            assertThat(entity.getIsVisibleToMembers()).isFalse();
            assertThat(entity.getIsSelfRecordable()).isTrue();
            assertThat(entity.getLinkedActivityFieldId()).isEqualTo(999L);
        }

        @Test
        @DisplayName("正常系: targetValue が変更されると targetAchievedNotified がリセットされる")
        void targetValue変更でtargetAchievedNotifiedリセット() {
            // Given
            PerformanceMetricEntity entity = createEntity();
            entity.markTargetAchievedNotified();
            assertThat(entity.getTargetAchievedNotified()).isTrue();

            // When: targetValue を変更（異なる値）
            entity.update("テスト指標", "km", MetricDataType.DECIMAL, AggregationType.SUM,
                    null, null,
                    new BigDecimal("999"), // 変更
                    null, null, 0, true, false, null);

            // Then: targetAchievedNotified がリセットされる
            assertThat(entity.getTargetAchievedNotified()).isFalse();
        }

        @Test
        @DisplayName("正常系: targetValue が同じ値なら targetAchievedNotified はリセットされない")
        void targetValue同値ならtargetAchievedNotifiedリセットされない() {
            // Given
            PerformanceMetricEntity entity = createEntity(); // targetValue = 100
            entity.markTargetAchievedNotified();
            assertThat(entity.getTargetAchievedNotified()).isTrue();

            // When: targetValue を同じ値で更新
            entity.update("テスト指標", "km", MetricDataType.DECIMAL, AggregationType.SUM,
                    null, null,
                    new BigDecimal("100"), // 同じ値
                    null, null, 0, true, false, null);

            // Then: targetAchievedNotified はリセットされない
            assertThat(entity.getTargetAchievedNotified()).isTrue();
        }

        @Test
        @DisplayName("正常系: targetValue が null の場合は targetAchievedNotified に影響なし")
        void targetValueがnullならtargetAchievedNotified影響なし() {
            // Given
            PerformanceMetricEntity entity = createEntity();
            entity.markTargetAchievedNotified();

            // When: targetValue = null を渡す
            entity.update("テスト指標", "km", MetricDataType.DECIMAL, AggregationType.SUM,
                    null, null,
                    null, // null
                    null, null, 0, true, false, null);

            // Then: targetAchievedNotified は変わらない
            assertThat(entity.getTargetAchievedNotified()).isTrue();
            assertThat(entity.getTargetValue()).isNull();
        }

        @Test
        @DisplayName("正常系: 元の targetValue が null で新しい値が設定される場合、targetAchievedNotified がリセットされる")
        void 元のtargetValueがnullで新値設定時リセット() {
            // Given
            PerformanceMetricEntity entity = PerformanceMetricEntity.builder()
                    .teamId(1L).name("指標").unit("km")
                    .dataType(MetricDataType.DECIMAL).aggregationType(AggregationType.SUM)
                    .targetValue(null)
                    .build();
            entity.markTargetAchievedNotified();

            // When: null → 新しい値
            entity.update("指標", "km", MetricDataType.DECIMAL, AggregationType.SUM,
                    null, null,
                    new BigDecimal("50"), // 新しい値
                    null, null, 0, true, false, null);

            // Then: targetAchievedNotified がリセットされる
            assertThat(entity.getTargetAchievedNotified()).isFalse();
        }
    }

    @Nested
    @DisplayName("deactivate")
    class Deactivate {

        @Test
        @DisplayName("正常系: isActiveがfalseになる")
        void deactivateするとisActiveがfalse() {
            // Given
            PerformanceMetricEntity entity = createEntity();
            assertThat(entity.getIsActive()).isTrue();

            // When
            entity.deactivate();

            // Then
            assertThat(entity.getIsActive()).isFalse();
        }
    }

    @Nested
    @DisplayName("updateSortOrder")
    class UpdateSortOrder {

        @Test
        @DisplayName("正常系: sortOrderが更新される")
        void sortOrderが更新される() {
            // Given
            PerformanceMetricEntity entity = createEntity();

            // When
            entity.updateSortOrder(10);

            // Then
            assertThat(entity.getSortOrder()).isEqualTo(10);
        }
    }

    @Nested
    @DisplayName("markTargetAchievedNotified")
    class MarkTargetAchievedNotified {

        @Test
        @DisplayName("正常系: targetAchievedNotifiedがtrueになる")
        void targetAchievedNotifiedがtrueになる() {
            // Given
            PerformanceMetricEntity entity = createEntity();
            assertThat(entity.getTargetAchievedNotified()).isFalse();

            // When
            entity.markTargetAchievedNotified();

            // Then
            assertThat(entity.getTargetAchievedNotified()).isTrue();
        }
    }
}
