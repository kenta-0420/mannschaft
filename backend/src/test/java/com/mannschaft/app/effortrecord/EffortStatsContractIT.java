package com.mannschaft.app.effortrecord;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * F06.4.1 PR-1 個人集計の契約（試練 / red 先行。CMP-261115-2145）。AC-11〜17。
 *
 * <p>親設計 §6.1 の固定例を<b>実 Repository で直接永続化</b>し（作成 API の実装に依存させず、集計部隊が単独で
 * green 化できるようにする）、実 Security・実 Controller・実 {@code EffortStatsService}・実 MySQL を通して
 * 数値文字列を厳密比較する。定義 snapshot は migration seed の正本 JSON をそのまま使う。</p>
 *
 * <p>FE 側の責務（割合 33.3/0.0/66.7 の HALF_UP 表示・週の月曜始まりの期間換算・null bucket を線で繋がない描画）は
 * BE 応答に現れないため AC-18 の FE 単体（出陣後）で扱う。BE は割合の前提となる分母・各カテゴリ値を検査する。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("F06.4.1 個人集計（AC-11〜17）")
class EffortStatsContractIT extends EffortRecordItSupport {

    private static final Instant T0 = Instant.parse("2026-11-01T00:00:00Z");

    /** 親設計 §6.1 の STUDY 固定例（2026-11-01〜03）。 */
    private void seedStudyFixture(Long owner) {
        seedRecord(owner, STUDY, LocalDate.of(2026, 11, 1), TOKYO, values("subject", "A", "duration", "30"), T0);
        seedRecord(owner, STUDY, LocalDate.of(2026, 11, 1), TOKYO, values("subject", "B", "duration", "0"), T0.plusSeconds(1));
        seedRecord(owner, STUDY, LocalDate.of(2026, 11, 1), TOKYO, values("subject", "A", "duration", null), T0.plusSeconds(2));
        seedRecord(owner, STUDY, LocalDate.of(2026, 11, 2), TOKYO, values("subject", null, "duration", "60"), T0.plusSeconds(3));
        // 2026-11-03 は記録なし。
    }

    // ═══════════════════════════════ AC-11 ═══════════════════════════════

    @Test
    @DisplayName("AC-11: §6.1 BAR は DAY 30.00(2)/60.00(1)/null(0)、total 90.00")
    void AC11_固定例のBAR() throws Exception {
        Long owner = user("固定例");
        seedStudyFixture(owner);

        JsonNode stats = stats(owner, "2026-11-01", "2026-11-03", STUDY, "duration", "BAR", null);
        assertThat(stats.path("chart").asText()).isEqualTo("BAR");
        assertThat(stats.path("grain").asText()).as("1〜62日は DAY").isEqualTo("DAY");
        assertThat(stats.path("definitionUuid").asText()).isEqualTo(STUDY.toString());
        assertThat(stats.path("fieldKey").asText()).isEqualTo("duration");
        assertThat(stats.path("semantic").asText()).isEqualTo("DURATION");
        assertThat(stats.path("dateFrom").asText()).isEqualTo("2026-11-01");
        assertThat(stats.path("dateTo").asText()).isEqualTo("2026-11-03");
        assertThat(stats.path("categories")).isEmpty();

        JsonNode buckets = stats.path("buckets");
        assertThat(buckets).hasSize(3);
        assertBucket(buckets.get(0), "2026-11-01", "2026-11-01", "30.00", 2);
        assertBucket(buckets.get(1), "2026-11-02", "2026-11-02", "60.00", 1);
        assertBucket(buckets.get(2), "2026-11-03", "2026-11-03", null, 0);
        assertThat(stats.path("total").asText()).isEqualTo("90.00");
        assertThat(stats.path("totalSampleCount").asLong()).isEqualTo(3);
        assertThat(stats.has("emptyReason") && stats.path("emptyReason").isNull()).isTrue();
    }

    @Test
    @DisplayName("AC-11: §6.1 PIE は 未指定60.00/A30.00/B0.00、分母90.00、未指定は実科目と衝突しない reserved key")
    void AC11_固定例のPIE() throws Exception {
        Long owner = user("固定例円");
        seedStudyFixture(owner);

        JsonNode stats = stats(owner, "2026-11-01", "2026-11-03", STUDY, "duration", "PIE", null);
        assertThat(stats.path("buckets")).as("PIE は buckets=[]").isEmpty();
        assertThat(stats.path("total").asText()).as("円の分母").isEqualTo("90.00");
        assertThat(stats.path("emptyReason").isNull()).isTrue();

        JsonNode categories = stats.path("categories");
        assertThat(categories).hasSize(3);
        // 値降順 / key 昇順。
        JsonNode unspecified = categories.get(0);
        assertThat(unspecified.path("value").asText()).isEqualTo("60.00");
        assertThat(unspecified.path("sampleCount").asLong()).isEqualTo(1);
        assertThat(unspecified.path("key").asText()).as("reserved key は実在科目名と衝突しない").isNotIn("A", "B", "");
        assertThat(categories.get(1).path("label").asText()).isEqualTo("A");
        assertThat(categories.get(1).path("value").asText()).isEqualTo("30.00");
        assertThat(categories.get(1).path("sampleCount").asLong()).as("A の null 時間は件数に含めない").isEqualTo(1);
        assertThat(categories.get(2).path("label").asText()).isEqualTo("B");
        assertThat(categories.get(2).path("value").asText()).as("明示0は 0.00 として残す").isEqualTo("0.00");
        assertThat(categories.get(2).path("sampleCount").asLong()).isEqualTo(1);
    }

    // ═══════════════════════════════ AC-12 ═══════════════════════════════

    @Test
    @DisplayName("AC-12: WEIGHT LINE は同日 createdAt,id 最新、後編集で順位不変、total=null、翌日 null")
    void AC12_体重は日内最新で後編集でも順位不変() throws Exception {
        Long owner = user("体重");
        Instant morning = Instant.parse("2026-11-09T23:00:00Z"); // JST 11/10 08:00
        Instant evening = Instant.parse("2026-11-10T11:00:00Z"); // JST 11/10 20:00
        UUID first = seedRecord(owner, WEIGHT, LocalDate.of(2026, 11, 10), TOKYO, values("weight", "60.50"), morning);
        seedRecord(owner, WEIGHT, LocalDate.of(2026, 11, 10), TOKYO, values("weight", "60.20"), evening);

        JsonNode stats = stats(owner, "2026-11-10", "2026-11-11", WEIGHT, "weight", "LINE", null);
        assertThat(stats.path("buckets")).hasSize(2);
        assertBucket(stats.path("buckets").get(0), "2026-11-10", "2026-11-10", "60.20", 2);
        assertBucket(stats.path("buckets").get(1), "2026-11-11", "2026-11-11", null, 0);
        assertThat(stats.path("total").isNull()).as("LINE の total は null").isTrue();
        assertThat(stats.path("emptyReason").isNull()).isTrue();

        // 朝の記録を後から編集（updatedAt が最新になる）しても createdAt 順は変わらない。
        tx(() -> em.createNativeQuery("UPDATE effort_records SET values_json = JSON_OBJECT('weight','60.40'), "
                        + "updated_at = :now, version = version + 1, content_revision = content_revision + 1 "
                        + "WHERE id = UNHEX(REPLACE(:id,'-',''))")
                .setParameter("now", Instant.parse("2026-11-11T00:00:00Z")).setParameter("id", first.toString()).executeUpdate());
        JsonNode after = stats(owner, "2026-11-10", "2026-11-11", WEIGHT, "weight", "LINE", null);
        assertBucket(after.path("buckets").get(0), "2026-11-10", "2026-11-10", "60.20", 2);
    }

    @Test
    @DisplayName("AC-12: 年 LINE は MONTH 粒度で月内最新、記録のない月は null（補間しない）")
    void AC12_年は月内最新() throws Exception {
        Long owner = user("体重年");
        seedRecord(owner, WEIGHT, LocalDate.of(2026, 3, 5), TOKYO, values("weight", "60.00"), Instant.parse("2026-03-05T00:00:00Z"));
        seedRecord(owner, WEIGHT, LocalDate.of(2026, 3, 20), TOKYO, values("weight", "59.00"), Instant.parse("2026-03-20T00:00:00Z"));
        seedRecord(owner, WEIGHT, LocalDate.of(2026, 5, 1), TOKYO, values("weight", "58.50"), Instant.parse("2026-05-01T00:00:00Z"));

        JsonNode stats = stats(owner, "2026-01-01", "2026-12-31", WEIGHT, "weight", "LINE", null);
        assertThat(stats.path("grain").asText()).as("63〜366日は MONTH").isEqualTo("MONTH");
        JsonNode buckets = stats.path("buckets");
        assertThat(buckets).hasSize(12);
        assertBucket(buckets.get(0), "2026-01-01", "2026-01-31", null, 0);
        assertBucket(buckets.get(1), "2026-02-01", "2026-02-28", null, 0);
        assertBucket(buckets.get(2), "2026-03-01", "2026-03-31", "59.00", 2);
        assertBucket(buckets.get(3), "2026-04-01", "2026-04-30", null, 0);
        assertBucket(buckets.get(4), "2026-05-01", "2026-05-31", "58.50", 1);
        assertThat(stats.path("total").isNull()).isTrue();
    }

    // ═══════════════════════════════ AC-13 ═══════════════════════════════

    @Test
    @DisplayName("AC-13: 任意1日・62日は DAY、63日・366日は MONTH")
    void AC13_期間から粒度を決める() throws Exception {
        Long owner = user("粒度");
        assertThat(stats(owner, "2026-11-01", "2026-11-01", STUDY, "duration", "BAR", null).path("buckets")).hasSize(1);
        JsonNode d62 = stats(owner, "2026-01-01", "2026-03-03", STUDY, "duration", "BAR", null);
        assertThat(d62.path("grain").asText()).isEqualTo("DAY");
        assertThat(d62.path("buckets")).hasSize(62);
        JsonNode d63 = stats(owner, "2026-01-01", "2026-03-04", STUDY, "duration", "BAR", null);
        assertThat(d63.path("grain").asText()).isEqualTo("MONTH");
        assertThat(d63.path("buckets")).hasSize(3);
        JsonNode d366 = stats(owner, "2028-01-01", "2028-12-31", STUDY, "duration", "BAR", null);
        assertThat(d366.path("grain").asText()).isEqualTo("MONTH");
        assertThat(d366.path("buckets")).hasSize(12);
        // 週/月ボタンが送る明示 DAY、年ボタンが送る MONTH も受理する。
        assertThat(stats(owner, "2026-11-02", "2026-11-08", STUDY, "duration", "BAR", "DAY").path("buckets")).hasSize(7);
        assertThat(stats(owner, "2026-01-01", "2026-12-31", STUDY, "duration", "BAR", "MONTH").path("buckets")).hasSize(12);
    }

    @ParameterizedTest(name = "AC-13 {0}〜{1} grain={2} は400")
    @CsvSource(value = {
            "2026-01-01,2027-01-02,NONE",   // 367日
            "2026-11-05,2026-11-04,NONE",   // from > to
            "2026-01-01,2026-03-04,DAY",    // 63日で DAY
            "2026-01-01,2027-01-02,MONTH"   // 367日で MONTH
    })
    @DisplayName("AC-13: 367日・from>to・63日以上の DAY は400")
    void AC13_期間上限と粒度不整合は400(String from, String to, String grain) throws Exception {
        Long owner = user("粒度不正");
        expectError(statsResponse(owner, from, to, STUDY, "duration", "BAR", "NONE".equals(grain) ? null : grain),
                400, "COMMON_001");
    }

    @Test
    @DisplayName("AC-13: 2028-02-29 は単独 bucket、端数月は範囲内だけを集計する")
    void AC13_閏日と端数月() throws Exception {
        Long owner = user("閏日");
        JsonNode leap = stats(owner, "2028-02-28", "2028-03-01", STUDY, "duration", "BAR", null);
        assertThat(leap.path("buckets")).hasSize(3);
        assertBucket(leap.path("buckets").get(1), "2028-02-29", "2028-02-29", null, 0);

        seedRecord(owner, STUDY, LocalDate.of(2026, 1, 10), TOKYO, values("subject", "数学", "duration", "100"), T0);
        seedRecord(owner, STUDY, LocalDate.of(2026, 1, 20), TOKYO, values("subject", "数学", "duration", "15"), T0);
        seedRecord(owner, STUDY, LocalDate.of(2026, 4, 10), TOKYO, values("subject", "数学", "duration", "5"), T0);
        seedRecord(owner, STUDY, LocalDate.of(2026, 4, 11), TOKYO, values("subject", "数学", "duration", "700"), T0);
        JsonNode partial = stats(owner, "2026-01-15", "2026-04-10", STUDY, "duration", "BAR", null);
        assertThat(partial.path("grain").asText()).isEqualTo("MONTH");
        JsonNode buckets = partial.path("buckets");
        assertThat(buckets).hasSize(4);
        assertBucket(buckets.get(0), "2026-01-15", "2026-01-31", "15.00", 1);
        assertBucket(buckets.get(1), "2026-02-01", "2026-02-28", null, 0);
        assertBucket(buckets.get(2), "2026-03-01", "2026-03-31", null, 0);
        assertBucket(buckets.get(3), "2026-04-01", "2026-04-10", "5.00", 1);
        assertThat(partial.path("total").asText()).isEqualTo("20.00");
    }

    // ═══════════════════════════════ AC-14 ═══════════════════════════════

    @Test
    @DisplayName("AC-14: 本人 TZ 変更後も recordedOn を再計算せず、DST 切替日も1日1 bucket")
    void AC14_TZ変更とDSTでも記録日を保持() throws Exception {
        Long owner = user("TZ変更");
        seedRecord(owner, STUDY, LocalDate.of(2026, 11, 5), TOKYO, values("subject", "数学", "duration", "40"),
                Instant.parse("2026-11-04T15:30:00Z")); // JST 11/05 00:30（UTC では 11/04）
        tx(() -> em.createNativeQuery("UPDATE users SET timezone = 'America/Los_Angeles' WHERE id = :id")
                .setParameter("id", owner).executeUpdate());
        JsonNode stats = stats(owner, "2026-11-04", "2026-11-05", STUDY, "duration", "BAR", null);
        assertBucket(stats.path("buckets").get(0), "2026-11-04", "2026-11-04", null, 0);
        assertBucket(stats.path("buckets").get(1), "2026-11-05", "2026-11-05", "40.00", 1);

        String ny = "America/New_York";
        seedRecord(owner, STUDY, LocalDate.of(2026, 3, 8), ny, values("subject", "数学", "duration", "10"),
                Instant.parse("2026-03-08T07:30:00Z"));
        seedRecord(owner, STUDY, LocalDate.of(2026, 3, 9), ny, values("subject", "数学", "duration", "20"),
                Instant.parse("2026-03-09T04:30:00Z"));
        JsonNode dst = stats(owner, "2026-03-07", "2026-03-09", STUDY, "duration", "BAR", null);
        assertThat(dst.path("buckets")).hasSize(3);
        assertBucket(dst.path("buckets").get(0), "2026-03-07", "2026-03-07", null, 0);
        assertBucket(dst.path("buckets").get(1), "2026-03-08", "2026-03-08", "10.00", 1);
        assertBucket(dst.path("buckets").get(2), "2026-03-09", "2026-03-09", "20.00", 1);
    }

    // ═══════════════════════════════ AC-15 ═══════════════════════════════

    @Test
    @DisplayName("AC-15: NO_DATA は total=null、BAR/PIE 全0は total=0.00 ZERO_TOTAL、LINE は値ありでも total=null・emptyReason=null")
    void AC15_空と全0の区別() throws Exception {
        Long empty = user("記録なし");
        JsonNode noData = stats(empty, "2026-11-01", "2026-11-07", STUDY, "duration", "BAR", null);
        assertThat(noData.path("total").isNull()).isTrue();
        assertThat(noData.path("emptyReason").asText()).isEqualTo("NO_DATA");
        assertThat(noData.path("totalSampleCount").asLong()).isZero();
        assertThat(noData.path("buckets")).hasSize(7);
        noData.path("buckets").forEach(b -> {
            assertThat(b.path("value").isNull()).isTrue();
            assertThat(b.path("sampleCount").asLong()).isZero();
        });
        JsonNode noDataPie = stats(empty, "2026-11-01", "2026-11-07", STUDY, "duration", "PIE", null);
        assertThat(noDataPie.path("total").isNull()).isTrue();
        assertThat(noDataPie.path("emptyReason").asText()).isEqualTo("NO_DATA");
        assertThat(noDataPie.path("categories")).isEmpty();
        JsonNode noDataLine = stats(empty, "2026-11-01", "2026-11-07", WEIGHT, "weight", "LINE", null);
        assertThat(noDataLine.path("total").isNull()).isTrue();
        assertThat(noDataLine.path("emptyReason").asText()).isEqualTo("NO_DATA");

        Long zero = user("全0");
        seedRecord(zero, STUDY, LocalDate.of(2026, 11, 2), TOKYO, values("subject", "数学", "duration", "0"), T0);
        JsonNode zeroBar = stats(zero, "2026-11-01", "2026-11-07", STUDY, "duration", "BAR", null);
        assertThat(zeroBar.path("total").asText()).isEqualTo("0.00");
        assertThat(zeroBar.path("emptyReason").asText()).isEqualTo("ZERO_TOTAL");
        assertBucket(zeroBar.path("buckets").get(1), "2026-11-02", "2026-11-02", "0.00", 1);
        assertBucket(zeroBar.path("buckets").get(0), "2026-11-01", "2026-11-01", null, 0);
        JsonNode zeroPie = stats(zero, "2026-11-01", "2026-11-07", STUDY, "duration", "PIE", null);
        assertThat(zeroPie.path("total").asText()).isEqualTo("0.00");
        assertThat(zeroPie.path("emptyReason").asText()).isEqualTo("ZERO_TOTAL");

        Long line = user("体重あり");
        seedRecord(line, WEIGHT, LocalDate.of(2026, 11, 3), TOKYO, values("weight", "55.00"), T0);
        JsonNode lineStats = stats(line, "2026-11-01", "2026-11-07", WEIGHT, "weight", "LINE", null);
        assertThat(lineStats.path("total").isNull()).isTrue();
        assertThat(lineStats.path("emptyReason").isNull()).isTrue();
    }

    @Test
    @DisplayName("AC-15: PIE の分母に null 時間を算入しない")
    void AC15_PIE分母にnullを入れない() throws Exception {
        Long owner = user("null分母");
        seedRecord(owner, STUDY, LocalDate.of(2026, 11, 1), TOKYO, values("subject", "A", "duration", null), T0);
        seedRecord(owner, STUDY, LocalDate.of(2026, 11, 1), TOKYO, values("subject", "B", "duration", "30"), T0.plusSeconds(1));
        JsonNode pie = stats(owner, "2026-11-01", "2026-11-01", STUDY, "duration", "PIE", null);
        assertThat(pie.path("total").asText()).isEqualTo("30.00");
        assertThat(pie.path("totalSampleCount").asLong()).isEqualTo(1);
        Map<String, String> byLabel = new LinkedHashMap<>();
        pie.path("categories").forEach(c -> byLabel.put(c.path("label").asText(), c.path("value").asText()));
        assertThat(byLabel).containsEntry("B", "30.00");
        assertThat(byLabel.getOrDefault("A", "0.00")).as("null だけの科目は値を持たない").isEqualTo("0.00");
    }

    // ═══════════════════════════════ AC-16 ═══════════════════════════════

    @ParameterizedTest(name = "AC-16 {0}.{1} × {2} は400")
    @CsvSource({
            "STUDY,duration,LINE",
            "STUDY,subject,BAR", "STUDY,subject,LINE", "STUDY,subject,PIE",
            "EXERCISE,duration,LINE", "EXERCISE,duration,PIE",
            "EXERCISE,intensity,BAR", "EXERCISE,intensity,LINE", "EXERCISE,intensity,PIE",
            "EXERCISE,menu,BAR", "EXERCISE,menu,LINE", "EXERCISE,menu,PIE",
            "WEIGHT,weight,BAR", "WEIGHT,weight,PIE",
            "STUDY,no_such_field,BAR"
    })
    @DisplayName("AC-16: §5.1 不可の chart×semantic・SUBJECT 対応なしの PIE・未知 field は400")
    void AC16_不可の組合せは400(String preset, String fieldKey, String chart) throws Exception {
        Long owner = user("不可");
        UUID definition = switch (preset) {
            case "STUDY" -> STUDY;
            case "EXERCISE" -> EXERCISE;
            default -> WEIGHT;
        };
        expectError(statsResponse(owner, "2026-11-01", "2026-11-07", definition, fieldKey, chart, null), 400, "COMMON_001");
    }

    @Test
    @DisplayName("AC-16: §5.1 可の組合せ（DURATION BAR / STUDY PIE / WEIGHT LINE）は200")
    void AC16_許可の組合せは200() throws Exception {
        Long owner = user("許可");
        stats(owner, "2026-11-01", "2026-11-07", STUDY, "duration", "BAR", null);
        stats(owner, "2026-11-01", "2026-11-07", EXERCISE, "duration", "BAR", null);
        stats(owner, "2026-11-01", "2026-11-07", STUDY, "duration", "PIE", null);
        stats(owner, "2026-11-01", "2026-11-07", WEIGHT, "weight", "LINE", null);
    }

    @Test
    @DisplayName("AC-16: NUMBER は aggregation=SUM のときだけ BAR 可、DATE/DATETIME/CHECKBOX/TEXT は全 chart 400")
    void AC16_NUMBERの集計可否はsnapshotで決まる() throws Exception {
        Long owner = user("NUMBER");
        UUID team = seedTeamDefinition();
        seedTeamRecord(owner, team, LocalDate.of(2026, 11, 2), values("reps", "12.50", "score", "3", "memo", "x",
                "held_on", "2026-11-02", "started_at", "2026-11-02T01:00:00Z", "done", "true"));

        JsonNode reps = stats(owner, "2026-11-01", "2026-11-07", team, "reps", "BAR", null);
        assertThat(reps.path("total").asText()).isEqualTo("12.50");
        assertThat(reps.path("semantic").asText()).isEqualTo("NUMBER");
        assertThat(reps.path("unit").asText()).isEqualTo("回");

        List<String[]> rejected = new ArrayList<>();
        rejected.add(new String[]{"score", "BAR"});
        for (String chart : List.of("LINE", "PIE")) {
            rejected.add(new String[]{"reps", chart});
            rejected.add(new String[]{"score", chart});
        }
        for (String key : List.of("memo", "held_on", "started_at", "done")) {
            for (String chart : List.of("BAR", "LINE", "PIE")) {
                rejected.add(new String[]{key, chart});
            }
        }
        for (String[] pair : rejected) {
            MockHttpServletResponse response = statsResponse(owner, "2026-11-01", "2026-11-07", team, pair[0], pair[1], null);
            assertThat(response.getStatus()).as(pair[0] + "×" + pair[1] + ": " + response.getContentAsString()).isEqualTo(400);
        }
    }

    @Test
    @DisplayName("AC-16: 異なる series（STUDY と EXERCISE の duration）を合算しない")
    void AC16_異seriesは合算しない() throws Exception {
        Long owner = user("非合算");
        seedRecord(owner, STUDY, LocalDate.of(2026, 11, 1), TOKYO, values("subject", "数学", "duration", "30"), T0);
        seedRecord(owner, EXERCISE, LocalDate.of(2026, 11, 1), TOKYO, values("duration", "45", "intensity", "5", "menu", "ラン"), T0);
        assertThat(stats(owner, "2026-11-01", "2026-11-01", STUDY, "duration", "BAR", null).path("total").asText()).isEqualTo("30.00");
        assertThat(stats(owner, "2026-11-01", "2026-11-01", EXERCISE, "duration", "BAR", null).path("total").asText()).isEqualTo("45.00");
    }

    // ═══════════════════════════════ AC-17 ═══════════════════════════════

    @Test
    @DisplayName("AC-17: BigDecimal で内部無丸め、出力は小数2桁の10進文字列")
    void AC17_小数2桁の10進文字列() throws Exception {
        Long owner = user("精度");
        UUID team = seedTeamDefinition();
        // double 加算なら 0.1+0.2 系の誤差や大きな桁での丸め落ちが出る組合せ。
        List<String> inputs = List.of("0.10", "0.20", "999999999.99", "999999999.99", "0.01");
        for (int i = 0; i < inputs.size(); i++) {
            seedTeamRecord(owner, team, LocalDate.of(2026, 11, 1), values("reps", inputs.get(i), "score", null, "memo", null,
                    "held_on", null, "started_at", null, "done", null));
        }
        seedTeamRecord(owner, team, LocalDate.of(2026, 11, 2), values("reps", "7", "score", null, "memo", null,
                "held_on", null, "started_at", null, "done", null));

        JsonNode stats = stats(owner, "2026-11-01", "2026-11-02", team, "reps", "BAR", null);
        JsonNode day1 = stats.path("buckets").get(0).path("value");
        assertThat(day1.isTextual()).as("数値は JSON 数値ではなく文字列").isTrue();
        assertThat(day1.asText()).isEqualTo("2000000000.29");
        assertThat(stats.path("buckets").get(1).path("value").asText()).as("整数入力も小数2桁").isEqualTo("7.00");
        assertThat(stats.path("total").asText()).isEqualTo("2000000007.29");
        assertThat(stats.path("total").isTextual()).isTrue();
    }

    @Test
    @DisplayName("AC-23: 定義変更で科目 field を付け替えても、PIE の内訳は各原本の凍結 revision の科目から引く")
    void AC23_PIEの科目は各原本の凍結revisionから解決する() throws Exception {
        Long owner = user("凍結科目");
        String dur = field("duration", "NUMBER", "DURATION", "分", "SUM", 0, "0", "1440");
        UUID team = seedRevisions(
                revisionJson("duration", "subj_a", dur, field("subj_a", "TEXT", "SUBJECT", "NONE", "NONE", 0, null, null)),
                // revision 2: 時間は残し、科目を別 field (subj_b) へ付け替える（旧 subj_a は科目でなくなる）。
                revisionJson("duration", "subj_b", dur, field("subj_a", "TEXT", "TEXT", "NONE", "NONE", 0, null, null),
                        field("subj_b", "TEXT", "SUBJECT", "NONE", "NONE", 0, null, null)));
        seedRevisionRecord(owner, team, 1, LocalDate.of(2026, 11, 2), values("duration", "30", "subj_a", "math"));
        seedRevisionRecord(owner, team, 2, LocalDate.of(2026, 11, 3), values("duration", "20", "subj_a", null, "subj_b", "eng"));

        JsonNode pie = stats(owner, "2026-11-01", "2026-11-07", team, "duration", "PIE", null);
        Map<String, String> byKey = new LinkedHashMap<>();
        pie.path("categories").forEach(c -> byKey.put(c.path("key").asText(), c.path("value").asText()));
        assertThat(byKey).as(pie.toString()).containsEntry("subject:math", "30.00").containsEntry("subject:eng", "20.00")
                .doesNotContainKey(com.mannschaft.app.effortrecord.service.EffortStatsService.UNSPECIFIED_SUBJECT_KEY);
    }

    @Test
    @DisplayName("AC-16: aggregation を NONE から SUM へ変えても、NONE で凍結された原本は BAR に合算しない")
    void AC16_SUM可否は各原本の凍結revisionで判定する() throws Exception {
        Long owner = user("凍結SUM");
        UUID team = seedRevisions(
                revisionJson(null, null, field("reps", "NUMBER", "NUMBER", "回", "NONE", 2, "0", "999")),
                revisionJson(null, null, field("reps", "NUMBER", "NUMBER", "回", "SUM", 2, "0", "999")));
        seedRevisionRecord(owner, team, 1, LocalDate.of(2026, 11, 2), values("reps", "3"));
        seedRevisionRecord(owner, team, 2, LocalDate.of(2026, 11, 2), values("reps", "4"));

        JsonNode bar = stats(owner, "2026-11-01", "2026-11-07", team, "reps", "BAR", null);
        assertThat(bar.path("total").asText()).as("SUM 設定後の 4 だけ（旧版の 3 を混ぜない）").isEqualTo("4.00");
    }

    // ─────────────────────────────────────────────────────────────

    /** 版ごとの定義 JSON を作る（TEAM。revision 番号は seedRevisions が採番して差し込む）。 */
    private static String revisionJson(String durationKey, String subjectKey, String... fields) {
        return "{\"definitionUuid\":\"@ID@\",\"revision\":@REV@,\"source\":\"TEAM\",\"presetCode\":null,"
                + "\"teamTemplateId\":990001,\"templateName\":\"練習記録\","
                + "\"durationFieldKey\":" + (durationKey == null ? "null" : "\"" + durationKey + "\"")
                + ",\"subjectFieldKey\":" + (subjectKey == null ? "null" : "\"" + subjectKey + "\"")
                + ",\"fields\":[" + String.join(",", fields) + "]}";
    }

    /** 複数 revision を持つ TEAM 定義を直接 seed する（current_revision は最後の版）。 */
    private UUID seedRevisions(String... templates) {
        UUID id = com.mannschaft.app.common.UuidV7.generate();
        tx(() -> {
            em.createNativeQuery("INSERT INTO effort_definitions (id, source_type, source_key, team_id, "
                            + "team_template_id, current_revision, retired_at, version, created_at, updated_at, deleted_at) "
                            + "VALUES (UNHEX(REPLACE(:id,'-','')), 'TEAM', :key, 990001, 990001, :cur, NULL, 0, "
                            + "UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), NULL)")
                    .setParameter("id", id.toString()).setParameter("key", "team:990001:template:" + id)
                    .setParameter("cur", templates.length).executeUpdate();
            for (int i = 0; i < templates.length; i++) {
                String json = templates[i].replace("@ID@", id.toString()).replace("@REV@", String.valueOf(i + 1));
                em.createNativeQuery("INSERT INTO effort_definition_revisions (id, definition_id, revision, definition_json, "
                                + "definition_hash, created_at, updated_at) VALUES (UNHEX(REPLACE(:rid,'-','')), "
                                + "UNHEX(REPLACE(:id,'-','')), :rev, :json, SHA2(:json, 256), UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))")
                        .setParameter("rid", com.mannschaft.app.common.UuidV7.generate().toString())
                        .setParameter("id", id.toString()).setParameter("rev", i + 1).setParameter("json", json)
                        .executeUpdate();
            }
            return null;
        });
        teamDefinitions.add(id);
        return id;
    }

    private void seedRevisionRecord(Long owner, UUID definition, int revision, LocalDate recordedOn, Map<String, String> values) {
        String snapshot = tx(() -> String.valueOf(em.createNativeQuery("SELECT CAST(definition_json AS CHAR) FROM "
                + "effort_definition_revisions WHERE definition_id = UNHEX(REPLACE(:id,'-','')) AND revision = :rev")
                .setParameter("id", definition.toString()).setParameter("rev", revision).getSingleResult()));
        tx(() -> records.saveAndFlush(com.mannschaft.app.effortrecord.entity.EffortRecordEntity.builder()
                .ownerUserId(owner).definitionId(definition).definitionRevision(revision).definitionSnapshot(snapshot)
                .recordedOn(recordedOn).recordTimezone(TOKYO).valuesJson(write(values))
                .createdAt(T0).updatedAt(T0).build()));
    }

    private JsonNode stats(Long owner, String from, String to, UUID definition, String fieldKey, String chart, String grain)
            throws Exception {
        return expect(statsResponse(owner, from, to, definition, fieldKey, chart, grain), 200).path("data");
    }

    private MockHttpServletResponse statsResponse(Long owner, String from, String to, UUID definition, String fieldKey,
                                                  String chart, String grain) throws Exception {
        MockHttpServletRequestBuilder request = get(ME + "/stats").param("date_from", from).param("date_to", to)
                .param("definition_uuid", definition.toString()).param("field_key", fieldKey).param("chart", chart);
        if (grain != null) {
            request.param("grain", grain);
        }
        return http(request, owner);
    }

    private static void assertBucket(JsonNode bucket, String start, String end, String value, long sampleCount) {
        assertThat(bucket.path("startOn").asText()).as(bucket.toString()).isEqualTo(start);
        assertThat(bucket.path("endOn").asText()).as(bucket.toString()).isEqualTo(end);
        if (value == null) {
            assertThat(bucket.has("value") && bucket.path("value").isNull()).as("未記録 bucket は value=null: " + bucket).isTrue();
        } else {
            assertThat(bucket.path("value").asText()).as(bucket.toString()).isEqualTo(value);
        }
        assertThat(bucket.path("sampleCount").asLong()).as(bucket.toString()).isEqualTo(sampleCount);
    }

    /**
     * NUMBER / DATE / DATETIME / CHECKBOX / TEXT を持つ TEAM 定義を直接 seed する（TEAM 定義の登録 API は PR-2）。
     * reps=NUMBER/SUM/回、score=NUMBER/NONE。series identity は定義 UUID ごとに独立させる。
     */
    private UUID seedTeamDefinition() {
        UUID id = com.mannschaft.app.common.UuidV7.generate();
        String json = "{\"definitionUuid\":\"" + id + "\",\"revision\":1,\"source\":\"TEAM\",\"presetCode\":null,"
                + "\"teamTemplateId\":990001,\"templateName\":\"練習記録\",\"durationFieldKey\":null,\"subjectFieldKey\":null,"
                + "\"fields\":["
                + field("reps", "NUMBER", "NUMBER", "回", "SUM", 2, "0", "999999999.99")
                + "," + field("score", "NUMBER", "NUMBER", "点", "NONE", 2, "0", "999999999.99")
                + "," + field("memo", "TEXT", "TEXT", "NONE", "NONE", 0, null, null)
                + "," + field("held_on", "DATE", "DATE", "NONE", "NONE", 0, null, null)
                + "," + field("started_at", "DATETIME", "DATETIME", "NONE", "NONE", 0, null, null)
                + "," + field("done", "CHECKBOX", "CHECKBOX", "NONE", "NONE", 0, null, null)
                + "]}";
        tx(() -> {
            // TEAM 定義は組織に依存しない（2026-11-18 マスター裁定。データ設計 §2: organization_id 列を持たない）。
            em.createNativeQuery("INSERT INTO effort_definitions (id, source_type, source_key, team_id, "
                            + "team_template_id, current_revision, retired_at, version, created_at, updated_at, deleted_at) "
                            + "VALUES (UNHEX(REPLACE(:id,'-','')), 'TEAM', :key, 990001, 990001, 1, NULL, 0, "
                            + "UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), NULL)")
                    .setParameter("id", id.toString()).setParameter("key", "team:990001:template:" + id).executeUpdate();
            em.createNativeQuery("INSERT INTO effort_definition_revisions (id, definition_id, revision, definition_json, "
                            + "definition_hash, created_at, updated_at) VALUES (UNHEX(REPLACE(:rid,'-','')), "
                            + "UNHEX(REPLACE(:id,'-','')), 1, :json, SHA2(:json, 256), UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))")
                    .setParameter("rid", com.mannschaft.app.common.UuidV7.generate().toString())
                    .setParameter("id", id.toString()).setParameter("json", json).executeUpdate();
            return null;
        });
        teamDefinitions.add(id);
        return id;
    }

    private final List<UUID> teamDefinitions = new ArrayList<>();

    @org.junit.jupiter.api.AfterEach
    void seedしたTEAM定義を片付ける() {
        for (UUID id : teamDefinitions) {
            tx(() -> {
                em.createNativeQuery("DELETE FROM effort_records WHERE definition_id = UNHEX(REPLACE(:id,'-',''))")
                        .setParameter("id", id.toString()).executeUpdate();
                em.createNativeQuery("DELETE FROM effort_definition_revisions WHERE definition_id = UNHEX(REPLACE(:id,'-',''))")
                        .setParameter("id", id.toString()).executeUpdate();
                em.createNativeQuery("DELETE FROM effort_definitions WHERE id = UNHEX(REPLACE(:id,'-',''))")
                        .setParameter("id", id.toString()).executeUpdate();
                return null;
            });
        }
        teamDefinitions.clear();
    }

    private void seedTeamRecord(Long owner, UUID definition, LocalDate recordedOn, Map<String, String> values) {
        String snapshot = tx(() -> String.valueOf(em.createNativeQuery("SELECT CAST(definition_json AS CHAR) FROM "
                + "effort_definition_revisions WHERE definition_id = UNHEX(REPLACE(:id,'-','')) AND revision = 1")
                .setParameter("id", definition.toString()).getSingleResult()));
        tx(() -> records.saveAndFlush(com.mannschaft.app.effortrecord.entity.EffortRecordEntity.builder()
                .ownerUserId(owner).definitionId(definition).definitionRevision(1).definitionSnapshot(snapshot)
                .recordedOn(recordedOn).recordTimezone(TOKYO).valuesJson(write(values))
                .createdAt(T0).updatedAt(T0).build()));
    }

    private static String field(String key, String type, String semantic, String unit, String aggregation, int scale,
                                String min, String max) {
        return "{\"fieldKey\":\"" + key + "\",\"label\":\"" + key + "\",\"labelKey\":null,\"type\":\"" + type
                + "\",\"required\":false,\"semantic\":\"" + semantic + "\",\"unit\":\"" + unit + "\",\"aggregation\":\""
                + aggregation + "\",\"scale\":" + scale + ",\"min\":" + (min == null ? "null" : "\"" + min + "\"")
                + ",\"max\":" + (max == null ? "null" : "\"" + max + "\"") + ",\"options\":[]}";
    }
}
