package com.mannschaft.app.health;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * F10.6 Phase 10-γ-② — MySQL DB サイズ監視 {@link HealthIndicator}。
 *
 * <h3>方式の選択（方式 B: DB サイズ取得）</h3>
 * {@code docker-compose.yml} において MySQL データディレクトリ ({@code /var/lib/mysql}) は
 * Docker 名前付きボリューム ({@code mysql-data}) でマウントされており、ホスト側パスへの
 * 直接マウントはない。そのため {@code java.io.File} でのディスク使用率計算（方式 A）は
 * アプリケーションコンテナから参照できず、JDBC 経由で {@code information_schema.TABLES} から
 * DB データサイズを取得する方式 B を採用する。
 *
 * <h3>判定基準</h3>
 * <ul>
 *   <li>{@code usedGb / maxGb < warn-threshold}: {@link Health#up()} — 正常</li>
 *   <li>{@code usedGb / maxGb >= warn-threshold} かつ {@code < error-threshold}:
 *       {@link Health#unknown()} — WARN（Spring の UNKNOWN ステータスは Actuator 上は
 *       UP 扱いだが、{@link HealthStatusListener} が UNKNOWN を UP と同等に扱うため
 *       DOWN 遷移の前段として検知できる）</li>
 *   <li>{@code usedGb / maxGb >= error-threshold}: {@link Health#down()} — CRITICAL</li>
 * </ul>
 *
 * <h3>設定キー</h3>
 * <pre>
 * mannschaft:
 *   disk-monitor:
 *     max-gb: ${MYSQL_MAX_SIZE_GB:10.0}
 *     warn-threshold: 0.75
 *     error-threshold: 0.90
 * </pre>
 *
 * <h3>HealthStatusListener との連携</h3>
 * {@link HealthStatusListener} が {@code /actuator/health} を 30 秒毎にポーリングし、
 * このインジケーターが {@code DOWN} を返した瞬間を UP→DOWN 遷移として検知し
 * {@code ErrorReportNotifier#notifyHealthDown("mysqlDisk", ...)} を自動発火する。
 * 追加設定不要。
 *
 * @see HealthStatusListener
 * @see org.springframework.boot.actuate.health.HealthIndicator
 */
@Component("mysqlDisk")
@Slf4j
public class MysqlDiskHealthIndicator implements HealthIndicator {

    private final JdbcTemplate jdbcTemplate;
    private final double maxGb;
    private final double warnThreshold;
    private final double errorThreshold;

    /**
     * クエリ: 現在の DB（{@code DATABASE()} で解決される）の全テーブルのデータサイズ合計 (GB)。
     *
     * <p>{@code information_schema.TABLES} は内部メタデータのため追加権限不要。
     * {@code TABLE_SCHEMA = DATABASE()} で接続中の DB に限定し、他 DB のデータを拾わない。</p>
     */
    static final String SIZE_QUERY =
            "SELECT COALESCE(SUM(DATA_LENGTH + INDEX_LENGTH), 0) / (1024.0 * 1024 * 1024) AS used_gb " +
            "FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE()";

    public MysqlDiskHealthIndicator(
            JdbcTemplate jdbcTemplate,
            @Value("${mannschaft.disk-monitor.max-gb:10.0}") double maxGb,
            @Value("${mannschaft.disk-monitor.warn-threshold:0.75}") double warnThreshold,
            @Value("${mannschaft.disk-monitor.error-threshold:0.90}") double errorThreshold) {
        this.jdbcTemplate = jdbcTemplate;
        this.maxGb = maxGb;
        this.warnThreshold = warnThreshold;
        this.errorThreshold = errorThreshold;
    }

    @Override
    public Health health() {
        try {
            Double usedGb = jdbcTemplate.queryForObject(SIZE_QUERY, Double.class);
            if (usedGb == null) {
                return Health.unknown()
                        .withDetail("message", "DB サイズ取得結果が null でした")
                        .build();
            }

            double ratio = maxGb > 0 ? usedGb / maxGb : 0.0;
            double usedGbRounded = Math.round(usedGb * 1000.0) / 1000.0;
            double ratioPercent = Math.round(ratio * 1000.0) / 10.0;

            Health.Builder builder = buildByRatio(ratio, usedGbRounded, ratioPercent);
            return builder.build();

        } catch (Exception e) {
            log.warn("MySQL DB サイズ取得失敗", e);
            return Health.down(e)
                    .withDetail("message", "information_schema からの DB サイズ取得に失敗しました")
                    .build();
        }
    }

    /**
     * 使用率に基づいてヘルスステータスを判定する。
     *
     * @param ratio        使用率（0.0〜1.0 以上）
     * @param usedGb       実使用量 (GB)
     * @param ratioPercent 使用率 (%)
     * @return ヘルスビルダー（{@code build()} 未呼び出し）
     */
    private Health.Builder buildByRatio(double ratio, double usedGb, double ratioPercent) {
        if (ratio >= errorThreshold) {
            log.error("MySQL DB サイズ CRITICAL: {}GB / {}GB ({}%)", usedGb, maxGb, ratioPercent);
            return Health.down()
                    .withDetail("usedGb", usedGb)
                    .withDetail("maxGb", maxGb)
                    .withDetail("usagePercent", ratioPercent)
                    .withDetail("threshold", "CRITICAL (>=" + (int) (errorThreshold * 100) + "%)");
        } else if (ratio >= warnThreshold) {
            log.warn("MySQL DB サイズ WARN: {}GB / {}GB ({}%)", usedGb, maxGb, ratioPercent);
            return Health.unknown()
                    .withDetail("usedGb", usedGb)
                    .withDetail("maxGb", maxGb)
                    .withDetail("usagePercent", ratioPercent)
                    .withDetail("threshold", "WARN (>=" + (int) (warnThreshold * 100) + "%)");
        } else {
            return Health.up()
                    .withDetail("usedGb", usedGb)
                    .withDetail("maxGb", maxGb)
                    .withDetail("usagePercent", ratioPercent);
        }
    }
}
