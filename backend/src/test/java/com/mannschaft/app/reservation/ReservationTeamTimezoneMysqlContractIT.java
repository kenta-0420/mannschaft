package com.mannschaft.app.reservation;

import com.mannschaft.app.common.timezone.TeamTimezoneResolver;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.zone.ZoneRulesProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

/**
 * CMP1730: 正式MySQLのTZ変換と既存Java規範を照合する環境契約の試練。
 *
 * <p>同じ基底クラスのcontext/container/Redis金型を継承する。SELECTのみで、DB行・TZ表・
 * Clock・サービス状態は変更しない。TZ情報がない環境はNULLのassert失敗とし、skipしない。
 * 本試練のGREENはSQL採用条件の一部であり、PENDING失効の修正GREENを意味しない。</p>
 */
@DisplayName("CMP1730 正式MySQLとJavaのTZ変換環境契約")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class ReservationTeamTimezoneMysqlContractIT extends AbstractMySqlIntegrationTest {

    private static final DateTimeFormatter WALL_FORMAT = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss");
    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");
    private static final ZoneId TOKYO = ZoneId.of("Asia/Tokyo");

    @Autowired private JdbcTemplate jdbc;
    @Autowired private TeamTimezoneResolver resolver;

    @Test
    @DisplayName("Tokyoの壁時計をUTCへ変換しJavaの同Instantと一致する")
    void Tokyo壁時計のUTC変換がJava規範と一致する() {
        String mysqlVersion = jdbc.queryForObject("SELECT VERSION()", String.class);
        System.out.printf("[CMP1730-TZ] MySQL=%s JavaTZDB=%s%n", mysqlVersion,
                ZoneRulesProvider.getVersions(NEW_YORK.getId()).lastKey());
        LocalDateTime wall = LocalDateTime.of(2026, 10, 3, 12, 0);

        Conversion actual = convert(wall, TOKYO);

        assertSoftly(softly -> {
            softly.assertThat(actual.utc()).as("TZ情報未搭載をskipしない").isNotNull();
            softly.assertThat(actual.utc()).isEqualTo(LocalDateTime.of(2026, 10, 3, 3, 0));
            softly.assertThat(actual.utc()).isEqualTo(javaUtc(wall, TOKYO));
            softly.assertThat(actual.roundtrip()).isEqualTo(wall);
        });
    }

    @Test
    @DisplayName("NY overlapはJavaと同じearlier offsetを選ぶ")
    void NY重複時刻はJavaと同じ早いoffsetを選ぶ() {
        LocalDateTime wall = LocalDateTime.of(2026, 11, 1, 1, 30);

        Conversion actual = convert(wall, NEW_YORK);

        assertSoftly(softly -> {
            softly.assertThat(actual.utc()).as("NY TZ情報未搭載をskipしない").isNotNull();
            softly.assertThat(actual.utc()).isEqualTo(LocalDateTime.of(2026, 11, 1, 5, 30));
            softly.assertThat(actual.utc()).isEqualTo(javaUtc(wall, NEW_YORK));
            softly.assertThat(actual.roundtrip()).isEqualTo(wall);
        });
    }

    @Test
    @DisplayName("NY gapはJavaが拒否しSQL roundtripで元壁時計へ戻らない")
    void NY欠落時刻はJavaが拒否しSQL往復が不一致になる() {
        LocalDateTime wall = LocalDateTime.of(2026, 3, 8, 2, 30);

        Conversion actual = convert(wall, NEW_YORK);

        assertThatThrownBy(() -> javaUtc(wall, NEW_YORK)).isInstanceOf(DateTimeException.class);
        assertSoftly(softly -> {
            softly.assertThat(actual.utc()).as("NULLとgap補正を区別する").isNotNull();
            softly.assertThat(actual.utc()).isEqualTo(LocalDateTime.of(2026, 3, 8, 7, 0));
            softly.assertThat(actual.roundtrip()).isNotNull().isNotEqualTo(wall);
            softly.assertThat(actual.roundtrip()).isEqualTo(LocalDateTime.of(2026, 3, 8, 3, 0));
        });
    }

    @ParameterizedTest(name = "MySQL変換範囲外の{0}年")
    @ValueSource(ints = {1960, 4000})
    @DisplayName("範囲外はNULLではなくno-opとなりroundtripだけでは識別できない")
    void MySQL変換範囲外は往復一致でもJava期限と一致しない(int year) {
        LocalDateTime wall = LocalDateTime.of(year, 1, 2, 12, 0);

        Conversion actual = convert(wall, TOKYO);

        assertSoftly(softly -> {
            softly.assertThat(actual.utc()).as("範囲外no-opはNULLではない").isNotNull().isEqualTo(wall);
            softly.assertThat(actual.utc()).isNotEqualTo(javaUtc(wall, TOKYO));
            softly.assertThat(actual.roundtrip()).as("範囲外でもroundtrip一致は成立する").isEqualTo(wall);
        });
    }

    private LocalDateTime javaUtc(LocalDateTime wall, ZoneId zone) {
        return LocalDateTime.ofInstant(resolver.toInstant(wall.toLocalDate(), wall.toLocalTime(), zone), ZoneOffset.UTC);
    }

    private Conversion convert(LocalDateTime wall, ZoneId zone) {
        // DATETIME壁時計を文字列でboundし、JDBC Timestamp/JVM default TZの変換を混ぜない。
        String value = wall.format(WALL_FORMAT);
        Conversion conversion = jdbc.queryForObject(
                "SELECT CONVERT_TZ(CAST(? AS DATETIME), ?, '+00:00'), "
                        + "CONVERT_TZ(CONVERT_TZ(CAST(? AS DATETIME), ?, '+00:00'), '+00:00', ?)",
                (rs, rowNum) -> new Conversion(rs.getObject(1, LocalDateTime.class), rs.getObject(2, LocalDateTime.class)),
                value, zone.getId(), value, zone.getId(), zone.getId());
        assertThat(conversion).isNotNull();
        return conversion;
    }

    private record Conversion(LocalDateTime utc, LocalDateTime roundtrip) {}
}
