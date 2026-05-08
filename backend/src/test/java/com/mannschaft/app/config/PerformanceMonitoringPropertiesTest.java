package com.mannschaft.app.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySource;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F10.5 Phase 10-β — {@link PerformanceMonitoringProperties} の単体テスト。
 *
 * <p>設計書 §5.2.3 のデフォルト値が保証され、外部設定によって正しくバインドされることを検証する。</p>
 */
@DisplayName("PerformanceMonitoringProperties (F10.5 Phase 10-β)")
class PerformanceMonitoringPropertiesTest {

    @Test
    @DisplayName("デフォルト値: warnMs=2000, errorMs=10000, warn-per-min=5, error-per-min=30, hit warn=0.80 / error=0.50")
    void default_values_match_design_recommendation() {
        PerformanceMonitoringProperties props = new PerformanceMonitoringProperties();

        assertThat(props.getRequest().getWarnMs()).isEqualTo(2_000L);
        assertThat(props.getRequest().getErrorMs()).isEqualTo(10_000L);
        assertThat(props.getSlowQuery().getWarnPerMinute()).isEqualTo(5);
        assertThat(props.getSlowQuery().getErrorPerMinute()).isEqualTo(30);
        assertThat(props.getCacheHitRate().getWarnThreshold()).isEqualTo(0.80);
        assertThat(props.getCacheHitRate().getErrorThreshold()).isEqualTo(0.50);
    }

    @Test
    @DisplayName("Binder で application.yml 相当の入れ子設定を読み込める")
    void binder_loadsNestedConfig() {
        Map<String, Object> source = Map.of(
                "mannschaft.performance-monitoring.request.warn-ms", "1500",
                "mannschaft.performance-monitoring.request.error-ms", "8000",
                "mannschaft.performance-monitoring.slow-query.warn-per-minute", "3",
                "mannschaft.performance-monitoring.slow-query.error-per-minute", "20",
                "mannschaft.performance-monitoring.cache-hit-rate.warn-threshold", "0.70",
                "mannschaft.performance-monitoring.cache-hit-rate.error-threshold", "0.40"
        );
        ConfigurationPropertySource ps = new MapConfigurationPropertySource(source);
        PerformanceMonitoringProperties bound = new Binder(ps)
                .bind("mannschaft.performance-monitoring", PerformanceMonitoringProperties.class)
                .get();

        assertThat(bound.getRequest().getWarnMs()).isEqualTo(1_500L);
        assertThat(bound.getRequest().getErrorMs()).isEqualTo(8_000L);
        assertThat(bound.getSlowQuery().getWarnPerMinute()).isEqualTo(3);
        assertThat(bound.getSlowQuery().getErrorPerMinute()).isEqualTo(20);
        assertThat(bound.getCacheHitRate().getWarnThreshold()).isEqualTo(0.70);
        assertThat(bound.getCacheHitRate().getErrorThreshold()).isEqualTo(0.40);
    }

    @Test
    @DisplayName("Setter 経由で個別フィールドを更新できる")
    void setters_work() {
        PerformanceMonitoringProperties props = new PerformanceMonitoringProperties();
        props.getRequest().setWarnMs(100L);
        props.getRequest().setErrorMs(200L);

        assertThat(props.getRequest().getWarnMs()).isEqualTo(100L);
        assertThat(props.getRequest().getErrorMs()).isEqualTo(200L);
    }
}
