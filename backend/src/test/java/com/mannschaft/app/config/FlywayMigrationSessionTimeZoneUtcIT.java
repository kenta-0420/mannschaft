package com.mannschaft.app.config;

import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 番人（CMP-260912-2258）: <b>アプリの DB 接続セッションの {@code time_zone} が UTC であり、
 * {@code NOW()} が {@code UTC_TIMESTAMP()} と同値である</b>ことを実 MySQL で固定する。
 *
 * <h2>なぜこの番人が要るのか</h2>
 * <p>本アプリの DB 格納基準は UTC 壁時計である（{@code TimeZoneStorageBasisGuardTest} 参照）。
 * ところが Flyway migration の DML は JPA を経由しないため、そこに書かれた {@code NOW()} は
 * <b>DB セッションの {@code time_zone} 設定</b>にそのまま従う。調査時点で migration には
 * {@code NOW()} 系が 65 ファイル・746 箇所あり、{@code UTC_TIMESTAMP()} は 1 箇所も無かった。</p>
 *
 * <p>それらが現在ずれていないのは、全環境でセッション {@code time_zone} が UTC に揃っているからである
 * （local: {@code docker-compose.yml} の {@code --default-time-zone=+00:00} / CI: ランナー UTC の
 * {@code SYSTEM} / test: Testcontainers の {@code mysql:8.0} が {@code SYSTEM}=UTC /
 * prod: RDS パラメータ {@code time_zone=UTC}）。つまり <b>746 箇所の正しさが 1 つの設定に丸ごとぶら下がっている</b>。
 * その設定は {@code TimeZoneStorageBasisGuardTest} が「設定ファイルの字面」としては守っているが、
 * <b>実際に張られた接続のセッション TZ がどうなっているか</b>は誰も測っていなかった。
 * JDBC の {@code serverTimezone} / {@code connectionTimeZone} や Connector/J の
 * {@code forceConnectionTimeZoneToSession} を触れば、字面を変えずにセッション TZ だけが動きうる。
 * 本テストはその<b>実測</b>を CI の不変条件にする。</p>
 *
 * <p>新規 migration が {@code NOW()} を増やすことは
 * {@code com.mannschaft.app.common.architecture.FlywayMigrationTimeFunctionGuardTest} が禁じる。
 * 本テストは既存 746 箇所を、あちらは将来の増加を守る（対になる 2 つの番人）。</p>
 */
@DisplayName("番人: DB接続セッションのTZがUTCで NOW()==UTC_TIMESTAMP() である（CMP-260912-2258）")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class FlywayMigrationSessionTimeZoneUtcIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("NOW() と UTC_TIMESTAMP() が同値である（migration の NOW() がずれていないことの実測）")
    void nowEqualsUtcTimestampOnApplicationConnection() {
        Integer diffSeconds = jdbc.queryForObject(
                "SELECT TIMESTAMPDIFF(SECOND, UTC_TIMESTAMP(), NOW())", Integer.class);

        assertThat(diffSeconds)
                .as("""
                    NOW() と UTC_TIMESTAMP() の差が 0 秒でない（実測差: %s 秒）。
                    DB 接続セッションの time_zone が UTC から外れている。
                    migration に書かれた NOW() 系 746 箇所がまるごとこの差だけずれて格納されるため、
                    接続設定（docker-compose の --default-time-zone / JDBC の serverTimezone /
                    Connector/J の forceConnectionTimeZoneToSession / RDS パラメータ time_zone）を
                    UTC へ戻すこと。本テストを緩めて通してはならない。""", diffSeconds)
                .isZero();
    }

    @Test
    @DisplayName("セッションの time_zone が UTC 相当に解決されている")
    void sessionTimeZoneResolvesToUtc() {
        String sessionTz = jdbc.queryForObject("SELECT @@session.time_zone", String.class);
        String globalTz = jdbc.queryForObject("SELECT @@global.time_zone", String.class);

        // SYSTEM の場合は値そのものからは UTC 判定できないため、実効オフセットで測る。
        Integer offsetSeconds = jdbc.queryForObject(
                "SELECT TIMESTAMPDIFF(SECOND, UTC_TIMESTAMP(), CURRENT_TIMESTAMP())", Integer.class);

        assertThat(offsetSeconds)
                .as("セッション time_zone の実効オフセットが 0 でない（session=%s, global=%s）。"
                        + "DB 格納基準 UTC（TimeZoneStorageBasisGuardTest）と食い違う。", sessionTz, globalTz)
                .isZero();
    }

    @Test
    @DisplayName("NOW() が返す壁時計は UTC の壁時計である（サーバ実時刻との突き合わせ）")
    void nowReturnsUtcWallClock() {
        LocalDateTime dbNow = jdbc.queryForObject("SELECT NOW()", LocalDateTime.class);
        LocalDateTime utcNow = LocalDateTime.now(java.time.ZoneOffset.UTC);

        assertThat(dbNow).isNotNull();
        long diffMinutes = Math.abs(java.time.Duration.between(dbNow, utcNow).toMinutes());

        // 許容差はテスト実行の揺らぎのみ。9 時間（540 分）のずれは決して吸収しない。
        assertThat(diffMinutes)
                .as("DB の NOW()=%s が UTC 壁時計=%s から %d 分ずれている。"
                        + "NOW() が UTC 以外の壁時計を返しており、migration の DML が格納基準を割る。",
                        dbNow, utcNow, diffMinutes)
                .isLessThanOrEqualTo(5);
    }
}
