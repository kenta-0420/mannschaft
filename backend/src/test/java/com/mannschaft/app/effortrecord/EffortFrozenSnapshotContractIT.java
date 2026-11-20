package com.mannschaft.app.effortrecord;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.http.MediaType;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * F06.4.1 PR-2 凍結 snapshot による既存原本の継続利用（試練 / red 先行。CMP-261115-2145）。AC-23（E09）。
 *
 * <p>チームが template を改名・field 削除・廃止しても、既存原本の GET（詳細・一覧）・個人集計・本人の値編集は
 * 原本作成時に凍結した snapshot で成功し続ける（親設計 §3「保存済み snapshot の値編集は廃止後も本人に許可」、
 * API 契約 §7「既存 snapshot の編集/個人集計は activity 照会に依存させない」）。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("F06.4.1 凍結 snapshot での継続利用（AC-23）")
class EffortFrozenSnapshotContractIT extends EffortRecordItSupport {

    private static final LocalDate DAY = LocalDate.of(2026, 11, 5);
    private static final String OPTIONS = "[\"数学\",\"英語\"]";

    private record World(Long team, Long admin, Long owner, Long template, JsonNode definition, String recordId) {
        String uuid() {
            return definition.path("definitionUuid").asText();
        }
    }

    private List<FieldSpec> fields() {
        return List.of(
                FieldSpec.select("subject", "科目", OPTIONS, 0),
                FieldSpec.number("minutes", "時間", 1),
                FieldSpec.text("memo", "メモ", 2));
    }

    /** 指標設定済み（時間=DURATION・科目=SUBJECT・メモ=MENU）の template で、本人が 数学 30 分の原本を1件作る。 */
    private World world(String name) throws Exception {
        Long team = team(name);
        Long admin = user(name + "管理者");
        teamAdmin(admin, team);
        Long owner = user(name + "本人");
        member(owner, team);
        Long template = teamTemplate(admin, team, "練習", fields());

        String url = "/api/v1/teams/" + team + "/effort-records/template-settings/" + template;
        JsonNode current = expect(http(get(url), admin), 200).path("data");
        JsonNode d = current.path("definition");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("version", current.path("version").asLong());
        body.put("fields", List.of(
                setting(keyOf(d, "科目"), "SUBJECT", "NONE", "NONE"),
                setting(keyOf(d, "時間"), "DURATION", "MINUTE", "SUM"),
                setting(keyOf(d, "メモ"), "MENU", "NONE", "NONE")));
        body.put("durationFieldKey", keyOf(d, "時間"));
        body.put("subjectFieldKey", keyOf(d, "科目"));
        expect(http(put(url).contentType(MediaType.APPLICATION_JSON).content(write(body)), admin), 200);

        JsonNode snap = snapshot(owner, team, template);
        JsonNode definition = snap.path("definition");
        String math = fieldOf(definition, "科目").path("options").get(0).path("key").asText();
        JsonNode created = createTeamRecord(owner, snap, DAY, Map.of("科目", math, "時間", "30", "メモ", "腕立て"));
        return new World(team, admin, owner, template, definition, created.path("id").asText());
    }

    private static Map<String, Object> setting(String fieldKey, String semantic, String unit, String aggregation) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("fieldKey", fieldKey);
        s.put("semantic", semantic);
        s.put("unit", unit);
        s.put("aggregation", aggregation);
        return s;
    }

    private JsonNode stats(World w, String chart) throws Exception {
        return expect(http(get(ME + "/stats").param("date_from", DAY.toString()).param("date_to", DAY.toString())
                .param("definition_uuid", w.uuid()).param("field_key", keyOf(w.definition(), "時間"))
                .param("chart", chart), w.owner()), 200).path("data");
    }

    /** 詳細・一覧・BAR/PIE 集計が凍結 snapshot（作成時の定義・値）で返ることを確かめる。 */
    private void assertFrozenReadable(World w, String expectedMinutes) throws Exception {
        JsonNode detail = expect(http(get(ME + "/" + w.recordId()), w.owner()), 200).path("data");
        assertThat(detail.path("definition")).as("詳細の定義は作成時の凍結 snapshot").isEqualTo(w.definition());
        assertThat(detail.at("/definition/templateName").asText()).isEqualTo("練習");
        assertThat(valueOf(detail, keyOf(w.definition(), "時間")).asText()).isEqualTo(expectedMinutes);

        List<String> listed = new ArrayList<>();
        expect(http(get(ME).param("definition_uuid", w.uuid()), w.owner()), 200).path("data")
                .forEach(r -> listed.add(r.path("id").asText()));
        assertThat(listed).containsExactly(w.recordId());

        assertThat(stats(w, "BAR").path("total").asText()).isEqualTo(expectedMinutes + ".00");
        JsonNode pie = stats(w, "PIE");
        assertThat(pie.path("categories")).hasSize(1);
        assertThat(pie.at("/categories/0/label").asText()).isEqualTo("数学");
        assertThat(pie.at("/categories/0/value").asText()).isEqualTo(expectedMinutes + ".00");
    }

    /** 本人が値を編集する（全置換 PUT）。凍結 snapshot の全 field を渡す。 */
    private JsonNode edit(World w, String minutes) throws Exception {
        JsonNode current = expect(http(get(ME + "/" + w.recordId()), w.owner()), 200).path("data");
        Map<String, String> byKey = new LinkedHashMap<>();
        for (JsonNode value : current.path("values")) {
            byKey.put(value.path("fieldKey").asText(), value.path("value").isNull() ? null : value.path("value").asText());
        }
        byKey.put(keyOf(w.definition(), "時間"), minutes);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("version", current.path("version").asLong());
        body.put("recordedOn", DAY.toString());
        body.put("values", valueList(byKey));
        body.put("privateNote", null);
        return expect(http(put(ME + "/" + w.recordId()).contentType(MediaType.APPLICATION_JSON).content(write(body)),
                w.owner()), 200).path("data");
    }

    @Test
    @DisplayName("AC-23: template 改名・label 変更後も既存原本の GET・集計・値編集は作成時の snapshot で成功する")
    void AC23_改名後も凍結snapshotで継続() throws Exception {
        World w = world("改名");
        List<FieldSpec> renamed = new ArrayList<>(fields());
        renamed.set(1, renamed.get(1).withLabel("練習時間"));
        updateTeamTemplate(w.admin(), w.template(), "自主練", renamed);
        JsonNode latest = snapshot(w.owner(), w.team(), w.template()).path("definition");
        assertThat(latest.path("revision").asInt()).as("新しい revision が出ている").isGreaterThan(w.definition().path("revision").asInt());

        assertFrozenReadable(w, "30");
        JsonNode edited = edit(w, "45");
        assertThat(edited.path("definition")).as("値編集でも定義は凍結のまま").isEqualTo(w.definition());
        assertThat(edited.path("contentRevision").asLong()).isEqualTo(2);
        assertFrozenReadable(w, "45");
    }

    @Test
    @DisplayName("AC-23: activity で field を削除しても既存原本の GET・集計（削除された field）・値編集は成功する")
    void AC23_field削除後も凍結snapshotで継続() throws Exception {
        World w = world("削除");
        // 時間 field を削除し、現行 revision から時間が消えた状態にする。
        updateTeamTemplate(w.admin(), w.template(), "練習",
                List.of(FieldSpec.select("subject", "科目", OPTIONS, 0), FieldSpec.text("memo", "メモ", 2)));
        JsonNode latest = snapshot(w.owner(), w.team(), w.template()).path("definition");
        assertThat(latest.path("fields")).hasSize(2);

        assertFrozenReadable(w, "30");
        edit(w, "50");
        assertFrozenReadable(w, "50");
    }

    @Test
    @DisplayName("AC-23: 科目 field を削除し時間 field が現行 revision に残っても、過去の PIE は凍結した科目定義で集計できる")
    void AC23_科目field削除後も過去のPIEは凍結定義で集計() throws Exception {
        World w = world("科目削除");
        // 科目 field だけを削除する。時間（DURATION）の key は現行 revision に残り、現行の subjectFieldKey は消える。
        updateTeamTemplate(w.admin(), w.template(), "練習",
                List.of(FieldSpec.number("minutes", "時間", 1), FieldSpec.text("memo", "メモ", 2)));
        JsonNode latest = snapshot(w.owner(), w.team(), w.template()).path("definition");
        String minutesKey = keyOf(w.definition(), "時間");
        assertThat(latest.path("revision").asInt()).as("新しい revision が出ている")
                .isGreaterThan(w.definition().path("revision").asInt());
        assertThat(fieldOf(latest, "時間").path("fieldKey").asText()).as("時間 key は現行 revision に残る").isEqualTo(minutesKey);
        assertThat(fieldOf(latest, "時間").path("semantic").asText()).isEqualTo("DURATION");
        assertThat(latest.path("subjectFieldKey").isNull()).as("現行 revision には科目が無い").isTrue();

        assertFrozenReadable(w, "30");
    }

    @Test
    @DisplayName("AC-23: template 廃止後も既存原本の GET・集計・値編集は成功し、不正な値の編集は 400 で保存値を変えない")
    void AC23_廃止後も凍結snapshotで継続() throws Exception {
        World w = world("廃止");
        retireTeamTemplate(w.admin(), w.template());
        expectError(postSnapshot(w.owner(), w.team(), w.template()), 409, "EFFORT_002");

        assertFrozenReadable(w, "30");

        // 途中失敗: 凍結 snapshot の上限（DURATION は 0〜1440）を超える値は 400、保存値は 30 のまま。
        JsonNode current = expect(http(get(ME + "/" + w.recordId()), w.owner()), 200).path("data");
        Map<String, String> byKey = new LinkedHashMap<>();
        for (JsonNode value : current.path("values")) {
            byKey.put(value.path("fieldKey").asText(), value.path("value").isNull() ? null : value.path("value").asText());
        }
        byKey.put(keyOf(w.definition(), "時間"), "1441");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("version", current.path("version").asLong());
        body.put("recordedOn", DAY.toString());
        body.put("values", valueList(byKey));
        body.put("privateNote", null);
        expectError(http(put(ME + "/" + w.recordId()).contentType(MediaType.APPLICATION_JSON).content(write(body)),
                w.owner()), 400, "COMMON_001");
        assertFrozenReadable(w, "30");

        edit(w, "60");
        assertFrozenReadable(w, "60");
    }
}
