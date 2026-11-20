package com.mannschaft.app.effortrecord;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.http.MediaType;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * F06.4.1 PR-2 series key の発番（試練 / red 先行。CMP-261115-2145）。AC-22（E09）。
 *
 * <p>series identity は definitionUuid・stable field key・canonical unit・semantic の4要素（親設計 §3）。
 * 台帳裁定④: field_key は effort 側で activity の field ID＋型＋単位から発番する。殿裁定（2026-11-18）で semantic も発番要素に加える。したがって
 * <ul>
 *   <li>改名（label 変更）は同じ key を保つ</li>
 *   <li>unit 変更、activity での field 削除→同じ activity key での再追加（別型・同型とも field ID が変わる）は新しい key</li>
 *   <li>semantic 変更も新しい key（旧 key と一致しないことまで固定する）で、旧 series と集計で混ぜない</li>
 *   <li>全 revision を通じて、同じ key が別の型・単位を指さない（key の使い回し禁止）</li>
 * </ul>
 * 集計の非混合は個人集計 API（実 Service）で確かめる。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("F06.4.1 series key の発番（AC-22）")
class EffortSeriesKeyContractIT extends EffortRecordItSupport {

    private static final LocalDate DAY = LocalDate.of(2026, 11, 4);

    private record World(Long team, Long admin, Long member, Long template) {
    }

    private World world(String name, List<FieldSpec> fields) {
        Long team = team(name);
        Long admin = user(name + "管理者");
        teamAdmin(admin, team);
        Long member = user(name + "部員");
        member(member, team);
        return new World(team, admin, member, teamTemplate(admin, team, name + "練習", fields));
    }

    private String settingsUrl(World w) {
        return "/api/v1/teams/" + w.team() + "/effort-records/template-settings/" + w.template();
    }

    /** 1 field だけの template に指標を設定し、応答の定義を返す。 */
    private JsonNode configure(World w, String label, String semantic, String unit, String aggregation) throws Exception {
        JsonNode current = expect(http(get(settingsUrl(w)), w.admin()), 200).path("data");
        Map<String, Object> setting = new LinkedHashMap<>();
        setting.put("fieldKey", keyOf(current.path("definition"), label));
        setting.put("semantic", semantic);
        setting.put("unit", unit);
        setting.put("aggregation", aggregation);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("version", current.path("version").asLong());
        body.put("fields", List.of(setting));
        body.put("durationFieldKey", "DURATION".equals(semantic) ? keyOf(current.path("definition"), label) : null);
        body.put("subjectFieldKey", null);
        return expect(http(put(settingsUrl(w)).contentType(MediaType.APPLICATION_JSON).content(write(body)), w.admin()), 200)
                .at("/data/definition");
    }

    private JsonNode barStats(Long owner, String definitionUuid, String fieldKey) throws Exception {
        return expect(http(get(ME + "/stats").param("date_from", DAY.toString()).param("date_to", DAY.toString())
                .param("definition_uuid", definitionUuid).param("field_key", fieldKey).param("chart", "BAR"), owner), 200)
                .path("data");
    }

    /** 全 revision を再読込し、同じ fieldKey が別の型・単位・semantic を指していないこと（key の使い回し禁止）。 */
    private void assertKeysNeverReused(String definitionUuid) throws Exception {
        Map<String, String> identity = new HashMap<>();
        for (JsonNode revision : storedRevisions(definitionUuid)) {
            for (JsonNode field : revision.path("fields")) {
                String key = field.path("fieldKey").asText();
                String typeAndUnit = field.path("type").asText() + "/" + field.path("unit").asText()
                        + "/" + field.path("semantic").asText();
                String previous = identity.putIfAbsent(key, typeAndUnit);
                assertThat(previous == null || previous.equals(typeAndUnit))
                        .as("key=" + key + " が " + previous + " と " + typeAndUnit + " の両方を指している").isTrue();
            }
        }
    }

    @Test
    @DisplayName("AC-22: 改名は同じ key、unit 変更は新しい key。集計は新 key の記録だけを合計する")
    void AC22_改名は同一key_unit変更は新key() throws Exception {
        World w = world("単位", List.of(FieldSpec.number("dist", "距離", 0)));
        JsonNode km = configure(w, "距離", "NUMBER", "KILOMETER", "SUM");
        String kmKey = keyOf(km, "距離");

        JsonNode kmSnap = snapshot(w.member(), w.team(), w.template());
        createTeamRecord(w.member(), kmSnap, DAY, Map.of("距離", "5"));

        // 改名（activity の label 変更）は同じ意味なので同じ key。
        updateTeamTemplate(w.admin(), w.template(), "単位練習", List.of(FieldSpec.number("dist", "走行距離", 0)));
        JsonNode renamed = snapshot(w.member(), w.team(), w.template()).path("definition");
        assertThat(keyOf(renamed, "走行距離")).as("label 変更で key は変えない").isEqualTo(kmKey);

        // unit 変更は新しい key。
        JsonNode meter = configure(w, "走行距離", "NUMBER", "METER", "SUM");
        String meterKey = keyOf(meter, "走行距離");
        assertThat(meterKey).as("unit 変更は新 key").isNotEqualTo(kmKey);

        JsonNode meterSnap = snapshot(w.member(), w.team(), w.template());
        assertThat(keyOf(meterSnap.path("definition"), "走行距離")).isEqualTo(meterKey);
        createTeamRecord(w.member(), meterSnap, DAY, Map.of("走行距離", "300"));

        String uuid = meter.path("definitionUuid").asText();
        JsonNode result = barStats(w.member(), uuid, meterKey);
        assertThat(result.path("unit").asText()).isEqualTo("METER");
        assertThat(result.path("total").asText()).as("KILOMETER の 5 を METER の series に混ぜない").isEqualTo("300.00");
        assertKeysNeverReused(uuid);
    }

    @Test
    @DisplayName("AC-22: activity で field を削除し同じ activity key で別型・同型を再追加すると、そのたび新しい key")
    void AC22_削除して同keyで再追加は新key() throws Exception {
        World w = world("再追加", List.of(FieldSpec.number("amount", "量", 0), FieldSpec.text("memo", "メモ", 1)));
        JsonNode original = snapshot(w.member(), w.team(), w.template()).path("definition");
        String uuid = original.path("definitionUuid").asText();
        String originalKey = keyOf(original, "量");
        String memoKey = keyOf(original, "メモ");

        // 削除
        updateTeamTemplate(w.admin(), w.template(), "再追加練習", List.of(FieldSpec.text("memo", "メモ", 1)));
        JsonNode removed = snapshot(w.member(), w.team(), w.template()).path("definition");
        assertThat(removed.path("fields")).hasSize(1);
        assertThat(keyOf(removed, "メモ")).as("残った field の key は保つ").isEqualTo(memoKey);

        // 同じ activity key "amount" を TEXT で再追加 → 新 key
        updateTeamTemplate(w.admin(), w.template(), "再追加練習",
                List.of(FieldSpec.text("amount", "量", 0), FieldSpec.text("memo", "メモ", 1)));
        JsonNode asText = snapshot(w.member(), w.team(), w.template()).path("definition");
        String textKey = keyOf(asText, "量");
        assertThat(fieldOf(asText, "量").path("type").asText()).isEqualTo("TEXT");
        assertThat(textKey).as("削除→同 key 別型の再追加は新 key").isNotEqualTo(originalKey);

        // もう一度削除して元と同じ NUMBER で再追加 → field ID が変わるので、元の key にも直前の key にも戻らない
        updateTeamTemplate(w.admin(), w.template(), "再追加練習", List.of(FieldSpec.text("memo", "メモ", 1)));
        snapshot(w.member(), w.team(), w.template());
        updateTeamTemplate(w.admin(), w.template(), "再追加練習",
                List.of(FieldSpec.number("amount", "量", 0), FieldSpec.text("memo", "メモ", 1)));
        JsonNode asNumber = snapshot(w.member(), w.team(), w.template()).path("definition");
        String numberKey = keyOf(asNumber, "量");
        assertThat(numberKey).isNotEqualTo(originalKey).isNotEqualTo(textKey);

        assertThat(asNumber.path("definitionUuid").asText()).as("同じテンプレートの定義は同じ UUID のまま").isEqualTo(uuid);
        assertKeysNeverReused(uuid);
    }

    @Test
    @DisplayName("AC-22: semantic 変更（NUMBER→DURATION）は新しい key を発番し、集計は旧 semantic の記録を混ぜない")
    void AC22_semantic変更は新keyで旧seriesと混ぜない() throws Exception {
        World w = world("意味", List.of(FieldSpec.number("min", "時間", 0)));
        String numberKey = keyOf(configure(w, "時間", "NUMBER", "MINUTE", "SUM"), "時間");
        JsonNode numberSnap = snapshot(w.member(), w.team(), w.template());
        createTeamRecord(w.member(), numberSnap, DAY, Map.of("時間", "10"));

        JsonNode duration = configure(w, "時間", "DURATION", "MINUTE", "SUM");
        String durationKey = keyOf(duration, "時間");
        assertThat(durationKey).as("semantic 変更（unit・型は同じ）でも新 key").isNotEqualTo(numberKey);
        assertThat(keyOf(numberSnap.path("definition"), "時間")).as("旧 revision の key は旧のまま").isEqualTo(numberKey);
        JsonNode durationSnap = snapshot(w.member(), w.team(), w.template());
        createTeamRecord(w.member(), durationSnap, DAY, Map.of("時間", "30"));

        assertThat(keyOf(durationSnap.path("definition"), "時間")).isEqualTo(durationKey);
        JsonNode result = barStats(w.member(), duration.path("definitionUuid").asText(), durationKey);
        assertThat(result.path("semantic").asText()).isEqualTo("DURATION");
        assertThat(result.path("total").asText()).as("NUMBER 時代の 10 を DURATION の series に混ぜない").isEqualTo("30.00");
        assertKeysNeverReused(duration.path("definitionUuid").asText());
    }
}
