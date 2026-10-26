package com.mannschaft.app.health;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

/**
 * F10.6 Phase 10-γ-② — {@link MysqlDiskHealthIndicator} の単体テスト。
 *
 * <p>JdbcTemplate をモック化し、正常 / WARN / CRITICAL の 3 ケースを検証する。
 * max-gb=10.0, warn-threshold=0.75, error-threshold=0.90 をテスト固定値とする。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MysqlDiskHealthIndicator 単体テスト (F10.6 Phase 10-γ-②)")
class MysqlDiskHealthIndicatorTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    /**
     * インジケーターを生成する（max-gb=10.0, warn=0.75, error=0.90）。
     */
    private MysqlDiskHealthIndicator indicator() {
        return new MysqlDiskHealthIndicator(jdbcTemplate, 10.0, 0.75, 0.90);
    }

    @Test
    @DisplayName("使用率 50% (5.0GB / 10GB): Health.up() を返す")
    void normal_50Percent_returnsUp() {
        // 5.0 GB = 50% < 75% (warn threshold)
        given(jdbcTemplate.queryForObject(eq(MysqlDiskHealthIndicator.SIZE_QUERY), eq(Double.class)))
                .willReturn(5.0);

        Health health = indicator().health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsKey("usedGb");
        assertThat(health.getDetails()).containsKey("maxGb");
        assertThat(health.getDetails()).containsKey("usagePercent");
        assertThat(health.getDetails().get("usagePercent")).isEqualTo(50.0);
    }

    @Test
    @DisplayName("使用率 78% (7.8GB / 10GB): Health.unknown() を返す (WARN)")
    void warn_78Percent_returnsUnknown() {
        // 7.8 GB = 78% >= 75% (warn threshold) かつ < 90% (error threshold)
        given(jdbcTemplate.queryForObject(eq(MysqlDiskHealthIndicator.SIZE_QUERY), eq(Double.class)))
                .willReturn(7.8);

        Health health = indicator().health();

        assertThat(health.getStatus()).isEqualTo(Status.UNKNOWN);
        assertThat(health.getDetails().get("usagePercent")).isEqualTo(78.0);
        assertThat(health.getDetails().get("threshold").toString()).contains("WARN");
    }

    @Test
    @DisplayName("使用率 92% (9.2GB / 10GB): Health.down() を返す (CRITICAL)")
    void critical_92Percent_returnsDown() {
        // 9.2 GB = 92% >= 90% (error threshold)
        given(jdbcTemplate.queryForObject(eq(MysqlDiskHealthIndicator.SIZE_QUERY), eq(Double.class)))
                .willReturn(9.2);

        Health health = indicator().health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails().get("usagePercent")).isEqualTo(92.0);
        assertThat(health.getDetails().get("threshold").toString()).contains("CRITICAL");
    }

    @Test
    @DisplayName("ちょうど warn 閾値 75% (7.5GB / 10GB): Health.unknown() を返す")
    void exactWarnThreshold_returnsUnknown() {
        // ちょうど 75% = warnThreshold（境界値テスト）
        given(jdbcTemplate.queryForObject(eq(MysqlDiskHealthIndicator.SIZE_QUERY), eq(Double.class)))
                .willReturn(7.5);

        Health health = indicator().health();

        assertThat(health.getStatus()).isEqualTo(Status.UNKNOWN);
    }

    @Test
    @DisplayName("ちょうど error 閾値 90% (9.0GB / 10GB): Health.down() を返す")
    void exactErrorThreshold_returnsDown() {
        // ちょうど 90% = errorThreshold（境界値テスト）
        given(jdbcTemplate.queryForObject(eq(MysqlDiskHealthIndicator.SIZE_QUERY), eq(Double.class)))
                .willReturn(9.0);

        Health health = indicator().health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
    }

    @Test
    @DisplayName("クエリ結果が null: Health.unknown() を返す")
    void nullResult_returnsUnknown() {
        given(jdbcTemplate.queryForObject(eq(MysqlDiskHealthIndicator.SIZE_QUERY), eq(Double.class)))
                .willReturn(null);

        Health health = indicator().health();

        assertThat(health.getStatus()).isEqualTo(Status.UNKNOWN);
        assertThat(health.getDetails().get("message").toString()).contains("null");
    }

    @Test
    @DisplayName("JdbcTemplate が例外: Health.down() を返す")
    void jdbcException_returnsDown() {
        given(jdbcTemplate.queryForObject(eq(MysqlDiskHealthIndicator.SIZE_QUERY), eq(Double.class)))
                .willThrow(new org.springframework.dao.DataAccessException("connection refused") {});

        Health health = indicator().health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsKey("message");
    }

    @Test
    @DisplayName("detail に usedGb / maxGb / usagePercent が含まれる")
    void health_containsDetailKeys() {
        given(jdbcTemplate.queryForObject(eq(MysqlDiskHealthIndicator.SIZE_QUERY), eq(Double.class)))
                .willReturn(3.0);

        Health health = indicator().health();

        assertThat(health.getDetails()).containsKeys("usedGb", "maxGb", "usagePercent");
        assertThat(health.getDetails().get("maxGb")).isEqualTo(10.0);
    }
}
