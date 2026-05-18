package com.mannschaft.app.weather.service;

import com.mannschaft.app.weather.repository.GeonamesMetadataRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link GeonamesImportService} の単体テスト。
 *
 * <p>TSV パース（{@code parseLine}）の正規化挙動を中心に検証する。
 * private メソッドのため {@link ReflectionTestUtils} 経由で呼び出す。</p>
 *
 * <p>2026-05-18 根治治療: マスタ書き込み時に JP 郵便番号が正規化されていなかったため、
 * 引き当てが恒常的に失敗していた。本テストはその回帰防止。</p>
 */
@DisplayName("GeonamesImportService 単体テスト")
@ExtendWith(MockitoExtension.class)
class GeonamesImportServiceTest {

    @Mock private JdbcTemplate jdbcTemplate;
    @Mock private GeonamesMetadataRepository geonamesMetadataRepository;

    /** GeoNames TSV の 12 カラムを生成するヘルパ。 */
    private static String tsvLine(String country, String postal, String place,
                                  String admin1, String admin2, String lat, String lon, String acc) {
        return String.join("\t",
                country, postal, place,
                admin1, "",        // admin1_name, admin1_code
                admin2, "",        // admin2_name, admin2_code
                "", "",            // admin3_name, admin3_code
                lat, lon, acc
        );
    }

    @Test
    @DisplayName("JP の hyphen 入り郵便番号は正規化形（7 桁・ハイフン無）で書き込まれる")
    void parseLine_normalizesJpPostalCode() {
        GeonamesImportService service =
                new GeonamesImportService(jdbcTemplate, geonamesMetadataRepository);
        String line = tsvLine("JP", "490-1401", "AisaiCity", "Aichi", "Aisai-shi",
                "35.16", "136.74", "4");

        Object[] row = ReflectionTestUtils.invokeMethod(service, "parseLine", line);

        assertThat(row).isNotNull();
        assertThat(row[0]).isEqualTo("JP");
        // ハイフン除去された 7 桁
        assertThat(row[1]).isEqualTo("4901401");
        assertThat(row[2]).isEqualTo("AisaiCity");
        assertThat(row[3]).isEqualTo("Aichi");
        assertThat(row[4]).isEqualTo("Aisai-shi");
        assertThat(row[5]).isEqualTo(new BigDecimal("35.16"));
        assertThat(row[6]).isEqualTo(new BigDecimal("136.74"));
        assertThat(row[7]).isEqualTo((short) 4);
    }

    @Test
    @DisplayName("JP の 7 桁未満は左ゼロパディングされる")
    void parseLine_padsShortJpPostalCode() {
        GeonamesImportService service =
                new GeonamesImportService(jdbcTemplate, geonamesMetadataRepository);
        String line = tsvLine("JP", "123", "X", "Y", "Z", "10.0", "20.0", "1");

        Object[] row = ReflectionTestUtils.invokeMethod(service, "parseLine", line);

        assertThat(row).isNotNull();
        assertThat(row[1]).isEqualTo("0000123");
    }

    @Test
    @DisplayName("非 JP は大文字化されるがハイフンは保持される")
    void parseLine_uppercasesNonJpPostalCode() {
        GeonamesImportService service =
                new GeonamesImportService(jdbcTemplate, geonamesMetadataRepository);
        String line = tsvLine("GB", "sw1a 1aa", "London", "England", "Westminster",
                "51.50", "-0.12", "6");

        Object[] row = ReflectionTestUtils.invokeMethod(service, "parseLine", line);

        assertThat(row).isNotNull();
        assertThat(row[0]).isEqualTo("GB");
        assertThat(row[1]).isEqualTo("SW1A 1AA");
    }

    @Test
    @DisplayName("国コード/郵便番号が空白の行は null（スキップ）")
    void parseLine_returnsNullForBlankCountryOrPostal() {
        GeonamesImportService service =
                new GeonamesImportService(jdbcTemplate, geonamesMetadataRepository);

        Object[] r1 = ReflectionTestUtils.invokeMethod(service, "parseLine",
                tsvLine("", "490-1401", "X", "Y", "Z", "1", "2", "3"));
        Object[] r2 = ReflectionTestUtils.invokeMethod(service, "parseLine",
                tsvLine("JP", "", "X", "Y", "Z", "1", "2", "3"));

        assertThat(r1).isNull();
        assertThat(r2).isNull();
    }

    @Test
    @DisplayName("緯度・経度が空白の行は null（スキップ）")
    void parseLine_returnsNullForMissingCoords() {
        GeonamesImportService service =
                new GeonamesImportService(jdbcTemplate, geonamesMetadataRepository);

        Object[] row = ReflectionTestUtils.invokeMethod(service, "parseLine",
                tsvLine("JP", "490-1401", "X", "Y", "Z", "", "", "3"));

        assertThat(row).isNull();
    }

    @Test
    @DisplayName("カラム数が 12 未満の行は null（破損行スキップ）")
    void parseLine_returnsNullForTooFewColumns() {
        GeonamesImportService service =
                new GeonamesImportService(jdbcTemplate, geonamesMetadataRepository);

        Object[] row = ReflectionTestUtils.invokeMethod(service, "parseLine",
                "JP\t490-1401\tplace");

        assertThat(row).isNull();
    }

    @Test
    @DisplayName("空行は null（スキップ）")
    void parseLine_returnsNullForEmptyLine() {
        GeonamesImportService service =
                new GeonamesImportService(jdbcTemplate, geonamesMetadataRepository);

        Object[] row = ReflectionTestUtils.invokeMethod(service, "parseLine", "");

        assertThat(row).isNull();
    }
}
