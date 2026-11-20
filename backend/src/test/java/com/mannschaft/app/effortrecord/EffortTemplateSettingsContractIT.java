package com.mannschaft.app.effortrecord;

import com.fasterxml.jackson.databind.JsonNode;
import com.mannschaft.app.activity.ActivityScopeType;
import com.mannschaft.app.activity.dto.CreateTemplateRequest;
import com.mannschaft.app.common.visibility.perf.SqlIntentCounter;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.support.test.MembershipTestHelper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * F06.4.1 PR-2 チームテンプレートの指標設定 API（試練 / red 先行。CMP-261115-2145）。AC-21（E09）と
 * PR-1 から持ち越した NUMBER / TEXT 等の semantic 境界（API 契約 §7・親設計 §3 の表）。
 *
 * <p>{@code GET/PUT /api/v1/teams/{teamId}/effort-records/template-settings/{templateId}} を実 Security・実 MySQL で叩く。
 * 認可: 既存テンプレート管理権限（ADMIN・DEPUTY_ADMIN）＋template の scope 一致。MEMBER・非所属・SYSTEM_ADMIN(非所属) は 403、
 * path の team に属さない（別チーム・組織スコープ・不在）template は 404 EFFORT_001。非所属者には template の存否で応答を変えない。
 * 親組織の有無・一致は判定に使わない（2026-11-18 マスター裁定）。主要成功系と別チーム拒否は親組織0件・2件の両方で通す。
 * 拒否・400 の後は version・revision が動かないこと（途中失敗で部分保存しない）を再読込で確かめる。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("F06.4.1 チームテンプレートの指標設定（AC-21・semantic 境界）")
class EffortTemplateSettingsContractIT extends EffortRecordItSupport {

    private record World(Long team, Long admin, Long member, Long template) {
    }

    private World world(String name, List<FieldSpec> fields) {
        return world(name, fields, TeamShape.STANDALONE);
    }

    private World world(String name, List<FieldSpec> fields, TeamShape shape) {
        Long team = team(shape, name);
        Long admin = user(name + "管理者");
        teamAdmin(admin, team);
        Long member = user(name + "部員");
        member(member, team);
        return new World(team, admin, member, teamTemplate(admin, team, name + "練習", fields));
    }

    private World standard(String name) {
        return standard(name, TeamShape.STANDALONE);
    }

    private World standard(String name, TeamShape shape) {
        return world(name, standardFields(), shape);
    }

    private static List<FieldSpec> standardFields() {
        return List.of(
                FieldSpec.select("subject", "科目", "[\"数学\",\"英語\"]", 0),
                FieldSpec.number("minutes", "時間", 1),
                FieldSpec.text("memo", "メモ", 2),
                FieldSpec.number("distance", "距離", 3));
    }

    private static String url(Long team, Long template) {
        return "/api/v1/teams/" + team + "/effort-records/template-settings/" + template;
    }

    private MockHttpServletResponse getSettings(Long actor, Long team, Long template) throws Exception {
        return http(get(url(team, template)), actor);
    }

    private MockHttpServletResponse putSettings(Long actor, Long team, Long template, Object body) throws Exception {
        return http(put(url(team, template)).contentType(MediaType.APPLICATION_JSON).content(write(body)), actor);
    }

    private JsonNode settings(Long actor, Long team, Long template) throws Exception {
        return expect(getSettings(actor, team, template), 200).path("data");
    }

    private static Map<String, Object> setting(String fieldKey, String semantic, String unit, String aggregation) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("fieldKey", fieldKey);
        s.put("semantic", semantic);
        s.put("unit", unit);
        s.put("aggregation", aggregation);
        return s;
    }

    private static Map<String, Object> body(long version, List<Map<String, Object>> fields, String duration, String subject) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("version", version);
        b.put("fields", fields);
        b.put("durationFieldKey", duration);
        b.put("subjectFieldKey", subject);
        return b;
    }

    /** 標準 template の正しい設定（時間=DURATION、科目=SUBJECT、メモ=MENU、距離=NUMBER/KILOMETER/SUM）。 */
    private Map<String, Object> validBody(JsonNode current) {
        JsonNode d = current.path("definition");
        return body(current.path("version").asLong(), List.of(
                        setting(keyOf(d, "科目"), "SUBJECT", "NONE", "NONE"),
                        setting(keyOf(d, "時間"), "DURATION", "MINUTE", "SUM"),
                        setting(keyOf(d, "メモ"), "MENU", "NONE", "NONE"),
                        setting(keyOf(d, "距離"), "NUMBER", "KILOMETER", "SUM")),
                keyOf(d, "時間"), keyOf(d, "科目"));
    }

    /** 拒否後に状態（version・定義）が変わっていないこと。 */
    private void assertUnchanged(World w, JsonNode before) throws Exception {
        assertThat(settings(w.admin(), w.team(), w.template())).as("拒否・失敗で設定は変わらない").isEqualTo(before);
    }

    // ═══════════════════════════════ 正常系 ═══════════════════════════════

    @ParameterizedTest(name = "AC-21: 親組織={0}")
    @EnumSource(TeamShape.class)
    @DisplayName("AC-21: ADMIN は GET/PUT 200。PUT の設定は再読込・snapshot API へ反映され、min/max/scale はサーバが決める（親組織0件・2件とも）")
    void AC21_ADMINはGETとPUTで200_再読込で反映(TeamShape shape) throws Exception {
        World w = standard("管理", shape);
        JsonNode current = settings(w.admin(), w.team(), w.template());
        assertThat(current.path("version").isIntegralNumber()).isTrue();
        assertThat(current.at("/definition/source").asText()).isEqualTo("TEAM");
        assertThat(current.at("/definition/teamTemplateId").asLong()).isEqualTo(w.template());
        assertThat(current.at("/definition/fields")).hasSize(4);

        JsonNode updated = expect(putSettings(w.admin(), w.team(), w.template(), validBody(current)), 200).path("data");
        JsonNode d = updated.path("definition");
        assertThat(updated.path("version").asLong()).isGreaterThan(current.path("version").asLong());

        JsonNode minutes = fieldOf(d, "時間");
        assertThat(minutes.path("semantic").asText()).isEqualTo("DURATION");
        assertThat(minutes.path("unit").asText()).isEqualTo("MINUTE");
        assertThat(minutes.path("aggregation").asText()).isEqualTo("SUM");
        // DURATION の固定規則（親設計 §3: 0〜1440 の整数分。標準プリセットと同じ表記）。
        assertThat(minutes.path("scale").asInt()).isZero();
        assertThat(minutes.path("min").asText()).isEqualTo("0");
        assertThat(minutes.path("max").asText()).isEqualTo("1440");
        assertThat(fieldOf(d, "科目").path("semantic").asText()).isEqualTo("SUBJECT");
        assertThat(fieldOf(d, "メモ").path("semantic").asText()).isEqualTo("MENU");
        JsonNode distance = fieldOf(d, "距離");
        assertThat(distance.path("semantic").asText()).isEqualTo("NUMBER");
        assertThat(distance.path("unit").asText()).isEqualTo("KILOMETER");
        assertThat(distance.path("aggregation").asText()).isEqualTo("SUM");
        assertThat(d.path("durationFieldKey").asText()).isEqualTo(keyOf(d, "時間"));
        assertThat(d.path("subjectFieldKey").asText()).isEqualTo(keyOf(d, "科目"));

        // 再読込（GET）で同じ設定が返る。
        assertThat(settings(w.admin(), w.team(), w.template())).isEqualTo(updated);
        // 部員の snapshot API にも同じ指標が載り、保存先は effort_definition_revisions.definition_json。
        JsonNode snap = snapshot(w.member(), w.team(), w.template()).path("definition");
        assertThat(snap.path("fields")).isEqualTo(d.path("fields"));
        assertThat(snap.path("durationFieldKey")).isEqualTo(d.path("durationFieldKey"));
        assertThat(storedRevision(snap.path("definitionUuid").asText(), snap.path("revision").asInt()).path("fields"))
                .isEqualTo(d.path("fields"));
    }

    @Test
    @DisplayName("AC-21: テンプレート管理権限を委任された DEPUTY_ADMIN は GET/PUT 200")
    void AC21_委任DEPUTYは200() throws Exception {
        World w = standard("副管理");
        Long deputy = user("副管理者");
        teamDeputy(deputy, w.team());

        JsonNode current = settings(deputy, w.team(), w.template());
        JsonNode updated = expect(putSettings(deputy, w.team(), w.template(), validBody(current)), 200).path("data");
        assertThat(fieldOf(updated.path("definition"), "時間").path("semantic").asText()).isEqualTo("DURATION");
    }

    // ═══════════════════════════════ 認可・IDOR ═══════════════════════════════

    @Test
    @DisplayName("AC-21: MEMBER・非所属・SYSTEM_ADMIN(非所属) は 403、未認証 401。非所属には template の存否で応答を変えない")
    void AC21_MEMBERと非所属は403() throws Exception {
        World w = standard("権限");
        JsonNode before = settings(w.admin(), w.team(), w.template());
        Map<String, Object> valid = validBody(before);
        Long outsider = user("部外者");
        member(outsider, team("部外チーム"));
        Long sysAdmin = systemAdmin("運営");

        expectError(getSettings(w.member(), w.team(), w.template()), 403, "COMMON_002");
        expectError(putSettings(w.member(), w.team(), w.template(), valid), 403, "COMMON_002");
        expectError(getSettings(outsider, w.team(), w.template()), 403, "COMMON_002");
        expectError(putSettings(outsider, w.team(), w.template(), valid), 403, "COMMON_002");
        expectError(getSettings(outsider, w.team(), Long.MAX_VALUE - 7), 403, "COMMON_002");
        expectError(getSettings(sysAdmin, w.team(), w.template()), 403, "COMMON_002");
        expectError(putSettings(sysAdmin, w.team(), w.template(), valid), 403, "COMMON_002");
        expect(getSettings(null, w.team(), w.template()), 401);
        expect(putSettings(null, w.team(), w.template(), valid), 401);

        assertUnchanged(w, before);
    }

    @ParameterizedTest(name = "AC-21: 親組織={0}")
    @EnumSource(TeamShape.class)
    @DisplayName("AC-21: path の team に属さない template（別チーム＝同じ親組織でも・組織スコープ・不在）は 404 EFFORT_001 で区別しない")
    void AC21_別チームのtemplateは404(TeamShape shape) throws Exception {
        World a = standard("甲", shape);
        // 乙は甲と同じ親組織に加盟させる（親組織0件なら独立チーム同士）。同じ組織に属することは何の許可にもならない。
        World b = standard("乙");
        parentOrganizations(a.team()).forEach(org -> attachToOrganization(b.team(), org));
        JsonNode bBefore = settings(b.admin(), b.team(), b.template());

        expectError(getSettings(a.admin(), a.team(), b.template()), 404, "EFFORT_001");
        expectError(putSettings(a.admin(), a.team(), b.template(), validBody(bBefore)), 404, "EFFORT_001");
        expectError(getSettings(a.admin(), a.team(), Long.MAX_VALUE - 7), 404, "EFFORT_001");

        // 組織スコープのテンプレート（甲の親組織のもの、親組織が無ければ無関係な組織のもの）も TEAM template として受けない。
        Long templateOrg = parentOrganizations(a.team()).isEmpty() ? organization("無関係組織") : parentOrganizations(a.team()).get(0);
        Long orgAdmin = user("組織管理者");
        tx(() -> {
            MembershipTestHelper.insertMembership(em, orgAdmin, ScopeType.ORGANIZATION, templateOrg, RoleKind.MEMBER);
            MembershipTestHelper.insertUserRole(em, orgAdmin, "ADMIN", null, templateOrg);
            return null;
        });
        Long orgTemplate = activityTemplates.createTemplate(orgAdmin, ActivityScopeType.ORGANIZATION, templateOrg,
                new CreateTemplateRequest("組織テンプレート", null, null, null, null, null,
                        List.of(new CreateTemplateRequest.TemplateFieldInput("n", "数", "NUMBER", false, null, null,
                                null, false, 0)))).getId();
        try {
            expectError(getSettings(a.admin(), a.team(), orgTemplate), 404, "EFFORT_001");
        } finally {
            tx(() -> {
                em.createNativeQuery("DELETE FROM activity_template_fields WHERE template_id = :id")
                        .setParameter("id", orgTemplate).executeUpdate();
                em.createNativeQuery("DELETE FROM activity_templates WHERE id = :id").setParameter("id", orgTemplate)
                        .executeUpdate();
                return null;
            });
        }

        assertUnchanged(b, bBefore);
    }

    // ═══════════════════════════════ semantic 境界（PR-1 持越し含む） ═══════════════════════════════

    @ParameterizedTest(name = "{0} 型に {1}/{2}/{3} → 許可={4}")
    @CsvSource({
            // NUMBER: DURATION / WEIGHT / INTENSITY / NUMBER のみ（親設計 §3・API 契約 §7）
            "NUMBER, DURATION, MINUTE, SUM, true",
            "NUMBER, WEIGHT, KILOGRAM, LATEST, true",
            "NUMBER, INTENSITY, RPE, NONE, true",
            "NUMBER, NUMBER, KILOMETER, SUM, true",
            "NUMBER, NUMBER, KILOMETER, NONE, true",
            "NUMBER, SUBJECT, NONE, NONE, false",
            "NUMBER, MENU, NONE, NONE, false",
            "NUMBER, TEXT, NONE, NONE, false",
            "NUMBER, DATE, NONE, NONE, false",
            "NUMBER, DATETIME, NONE, NONE, false",
            "NUMBER, CHECKBOX, NONE, NONE, false",
            // TEXT: SUBJECT / MENU / TEXT のみ。数値 semantic は不可
            "TEXT, SUBJECT, NONE, NONE, true",
            "TEXT, MENU, NONE, NONE, true",
            "TEXT, TEXT, NONE, NONE, true",
            "TEXT, DURATION, MINUTE, SUM, false",
            "TEXT, WEIGHT, KILOGRAM, LATEST, false",
            "TEXT, INTENSITY, RPE, NONE, false",
            "TEXT, NUMBER, KILOMETER, SUM, false",
            "TEXT, DATE, NONE, NONE, false",
            // SELECT: 数値 semantic・MENU は不可、SUBJECT は可
            "SELECT, SUBJECT, NONE, NONE, true",
            "SELECT, DURATION, MINUTE, SUM, false",
            "SELECT, WEIGHT, KILOGRAM, LATEST, false",
            "SELECT, INTENSITY, RPE, NONE, false",
            "SELECT, NUMBER, KILOMETER, SUM, false",
            "SELECT, MENU, NONE, NONE, false",
            // DATE / DATETIME / CHECKBOX は同名 semantic のみ・aggregation=NONE
            "DATE, DATE, NONE, NONE, true",
            "DATE, DURATION, MINUTE, SUM, false",
            "DATE, DATE, NONE, SUM, false",
            "DATETIME, DATETIME, NONE, NONE, true",
            "DATETIME, NUMBER, KILOMETER, SUM, false",
            "CHECKBOX, CHECKBOX, NONE, NONE, true",
            "CHECKBOX, CHECKBOX, NONE, SUM, false",
            // 固定規則: DURATION=MINUTE/SUM、WEIGHT=KILOGRAM/LATEST（平均・SUM にしない）、INTENSITY は時間と足さない、
            // 自由 TEXT は aggregation=NONE、NUMBER の unit は空不可
            "NUMBER, DURATION, HOUR, SUM, false",
            "NUMBER, WEIGHT, KILOGRAM, SUM, false",
            "NUMBER, WEIGHT, POUND, LATEST, false",
            "NUMBER, INTENSITY, RPE, SUM, false",
            "TEXT, TEXT, NONE, SUM, false",
            "TEXT, MENU, NONE, LATEST, false",
            "NUMBER, NUMBER, '', SUM, false",
    })
    @DisplayName("AC-21/semantic 境界: 型×semantic×unit×aggregation の許容組合せだけ 200、それ以外は 400 で何も保存しない")
    void AC21_semantic境界(String type, String semantic, String unit, String aggregation, boolean allowed) throws Exception {
        World w = world("境界" + type, List.of(FieldSpec.of("f", "項目", type, 0)));
        JsonNode before = settings(w.admin(), w.team(), w.template());
        String key = keyOf(before.path("definition"), "項目");

        MockHttpServletResponse response = putSettings(w.admin(), w.team(), w.template(),
                body(before.path("version").asLong(), List.of(setting(key, semantic, unit, aggregation)), null, null));

        if (allowed) {
            JsonNode field = fieldOf(expect(response, 200).at("/data/definition"), "項目");
            assertThat(field.path("type").asText()).isEqualTo(type);
            assertThat(field.path("semantic").asText()).isEqualTo(semantic);
            assertThat(field.path("unit").asText()).isEqualTo(unit);
            assertThat(field.path("aggregation").asText()).isEqualTo(aggregation);
        } else {
            JsonNode error = expectError(response, 400, "COMMON_001");
            assertThat(error.at("/error/fieldErrors").toString()).as("該当 field を指す fieldErrors").contains("fields");
            assertUnchanged(w, before);
        }
    }

    @Test
    @DisplayName("AC-21: NUMBER の unit は最大 32 字（33 字は 400）")
    void AC21_unitは32字まで() throws Exception {
        World w = world("単位長", List.of(FieldSpec.number("f", "項目", 0)));
        JsonNode before = settings(w.admin(), w.team(), w.template());
        String key = keyOf(before.path("definition"), "項目");
        long version = before.path("version").asLong();

        expectError(putSettings(w.admin(), w.team(), w.template(),
                body(version, List.of(setting(key, "NUMBER", "U".repeat(33), "SUM")), null, null)), 400, "COMMON_001");
        assertUnchanged(w, before);
        JsonNode ok = expect(putSettings(w.admin(), w.team(), w.template(),
                body(version, List.of(setting(key, "NUMBER", "U".repeat(32), "SUM")), null, null)), 200).path("data");
        assertThat(fieldOf(ok.path("definition"), "項目").path("unit").asText()).isEqualTo("U".repeat(32));
    }

    // ═══════════════════════════════ duration / subject 対応 ═══════════════════════════════

    @Test
    @DisplayName("AC-21: duration / subject の二重指定・対応先の semantic 不一致・未知 key は 400")
    void AC21_durationとsubjectの二重指定は400() throws Exception {
        World w = world("対応", List.of(
                FieldSpec.number("a", "時間A", 0),
                FieldSpec.number("b", "時間B", 1),
                FieldSpec.select("s", "科目", "[\"数学\"]", 2),
                FieldSpec.text("t", "科目2", 3)));
        JsonNode before = settings(w.admin(), w.team(), w.template());
        JsonNode d = before.path("definition");
        long v = before.path("version").asLong();
        String a = keyOf(d, "時間A");
        String b = keyOf(d, "時間B");
        String s = keyOf(d, "科目");
        String t = keyOf(d, "科目2");

        // DURATION が 2 項目
        expectError(putSettings(w.admin(), w.team(), w.template(), body(v, List.of(
                setting(a, "DURATION", "MINUTE", "SUM"), setting(b, "DURATION", "MINUTE", "SUM"),
                setting(s, "SUBJECT", "NONE", "NONE"), setting(t, "TEXT", "NONE", "NONE")), a, s)), 400, "COMMON_001");
        // SUBJECT が 2 項目
        expectError(putSettings(w.admin(), w.team(), w.template(), body(v, List.of(
                setting(a, "DURATION", "MINUTE", "SUM"), setting(b, "NUMBER", "KILOMETER", "SUM"),
                setting(s, "SUBJECT", "NONE", "NONE"), setting(t, "SUBJECT", "NONE", "NONE")), a, s)), 400, "COMMON_001");
        // durationFieldKey が DURATION でない field を指す
        expectError(putSettings(w.admin(), w.team(), w.template(), body(v, List.of(
                setting(a, "DURATION", "MINUTE", "SUM"), setting(b, "NUMBER", "KILOMETER", "SUM"),
                setting(s, "SUBJECT", "NONE", "NONE"), setting(t, "TEXT", "NONE", "NONE")), b, s)), 400, "COMMON_001");
        // subjectFieldKey が SUBJECT でない field を指す
        expectError(putSettings(w.admin(), w.team(), w.template(), body(v, List.of(
                setting(a, "DURATION", "MINUTE", "SUM"), setting(b, "NUMBER", "KILOMETER", "SUM"),
                setting(s, "SUBJECT", "NONE", "NONE"), setting(t, "TEXT", "NONE", "NONE")), a, t)), 400, "COMMON_001");
        // 対応先が未知 key
        expectError(putSettings(w.admin(), w.team(), w.template(), body(v, List.of(
                setting(a, "DURATION", "MINUTE", "SUM"), setting(b, "NUMBER", "KILOMETER", "SUM"),
                setting(s, "SUBJECT", "NONE", "NONE"), setting(t, "TEXT", "NONE", "NONE")), "zz_unknown", s)), 400, "COMMON_001");
        assertUnchanged(w, before);

        // 対照: 1 対 1 なら 200。
        JsonNode ok = expect(putSettings(w.admin(), w.team(), w.template(), body(v, List.of(
                setting(a, "DURATION", "MINUTE", "SUM"), setting(b, "NUMBER", "KILOMETER", "SUM"),
                setting(s, "SUBJECT", "NONE", "NONE"), setting(t, "TEXT", "NONE", "NONE")), a, s)), 200).path("data");
        JsonNode okDef = ok.path("definition");
        assertThat(okDef.path("durationFieldKey").asText()).isEqualTo(keyOf(okDef, "時間A"));
        assertThat(okDef.path("subjectFieldKey").asText()).isEqualTo(keyOf(okDef, "科目"));
    }

    // ═══════════════════════════════ 入力検証（空・null・未知） ═══════════════════════════════

    @Test
    @DisplayName("AC-21: 未知 key・重複 key・設定漏れ・空 fields・null・未知 enum・未知プロパティは 400 で何も保存しない")
    void AC21_未知key重複設定漏れは400() throws Exception {
        World w = standard("検証");
        JsonNode before = settings(w.admin(), w.team(), w.template());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> valid = (List<Map<String, Object>>) validBody(before).get("fields");
        long v = before.path("version").asLong();
        JsonNode d = before.path("definition");
        String duration = keyOf(d, "時間");
        String subject = keyOf(d, "科目");

        List<Map<String, Object>> unknownKey = new ArrayList<>(valid);
        unknownKey.add(setting("zz_unknown", "TEXT", "NONE", "NONE"));
        expectError(putSettings(w.admin(), w.team(), w.template(), body(v, unknownKey, duration, subject)), 400, "COMMON_001");

        List<Map<String, Object>> duplicated = new ArrayList<>(valid);
        duplicated.add(valid.get(2));
        expectError(putSettings(w.admin(), w.team(), w.template(), body(v, duplicated, duration, subject)), 400, "COMMON_001");

        List<Map<String, Object>> missing = new ArrayList<>(valid.subList(0, 3));
        expectError(putSettings(w.admin(), w.team(), w.template(), body(v, missing, duration, subject)), 400, "COMMON_001");

        expectError(putSettings(w.admin(), w.team(), w.template(), body(v, List.of(), null, null)), 400, "COMMON_001");

        Map<String, Object> nullFields = body(v, valid, duration, subject);
        nullFields.put("fields", null);
        expectError(putSettings(w.admin(), w.team(), w.template(), nullFields), 400, "COMMON_001");

        Map<String, Object> nullVersion = body(v, valid, duration, subject);
        nullVersion.put("version", null);
        expectError(putSettings(w.admin(), w.team(), w.template(), nullVersion), 400, "COMMON_001");

        List<Map<String, Object>> unknownEnum = new ArrayList<>(valid);
        unknownEnum.set(3, setting(keyOf(d, "距離"), "FOO", "KILOMETER", "SUM"));
        expectError(putSettings(w.admin(), w.team(), w.template(), body(v, unknownEnum, duration, subject)), 400, "COMMON_001");

        List<Map<String, Object>> nullSemantic = new ArrayList<>(valid);
        nullSemantic.set(3, setting(keyOf(d, "距離"), null, "KILOMETER", "SUM"));
        expectError(putSettings(w.admin(), w.team(), w.template(), body(v, nullSemantic, duration, subject)), 400, "COMMON_001");

        Map<String, Object> unknownProperty = body(v, valid, duration, subject);
        unknownProperty.put("min", "0");
        expectError(putSettings(w.admin(), w.team(), w.template(), unknownProperty), 400, "COMMON_001");

        List<Map<String, Object>> fieldExtra = new ArrayList<>(valid);
        Map<String, Object> withScale = new LinkedHashMap<>(valid.get(1));
        withScale.put("scale", 3);
        fieldExtra.set(1, withScale);
        expectError(putSettings(w.admin(), w.team(), w.template(), body(v, fieldExtra, duration, subject)), 400, "COMMON_001");

        assertUnchanged(w, before);
        // 対照: 正しい本文なら 200。
        expect(putSettings(w.admin(), w.team(), w.template(), body(v, valid, duration, subject)), 200);
    }

    @Test
    @DisplayName("AC-21: 古い version の PUT は 409 COMMON_003、先勝ちの設定を保持する")
    void AC21_version競合は409() throws Exception {
        World w = standard("競合");
        JsonNode before = settings(w.admin(), w.team(), w.template());
        Map<String, Object> first = validBody(before);
        JsonNode won = expect(putSettings(w.admin(), w.team(), w.template(), first), 200).path("data");

        // 同じ（古い）version で別内容を送る。
        Map<String, Object> stale = validBody(before);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> fields = new ArrayList<>((List<Map<String, Object>>) stale.get("fields"));
        fields.set(3, setting(keyOf(before.path("definition"), "距離"), "NUMBER", "METER", "SUM"));
        stale.put("fields", fields);
        expectError(putSettings(w.admin(), w.team(), w.template(), stale), 409, "COMMON_003");

        assertThat(settings(w.admin(), w.team(), w.template())).isEqualTo(won);
    }

    // ═══════════════════════════════ activity 既存列の不変 ═══════════════════════════════

    @Test
    @DisplayName("AC-21: 指標設定は activity テンプレートの既存列・行を変えず、列も足さない（保存先は effort 側）")
    void AC21_activityテンプレートの既存列は不変() throws Exception {
        World w = standard("不変");
        String templateRow = activityRow("SELECT CONCAT_WS('|', id, scope_type, scope_id, name, IFNULL(description,''), "
                + "is_participant_required, default_visibility, sort_order, created_by, IFNULL(deleted_at,''), updated_at) "
                + "FROM activity_templates WHERE id = " + w.template());
        String fieldRows = activityRow("SELECT GROUP_CONCAT(CONCAT_WS('|', id, field_key, field_label, field_type, is_required, "
                + "IFNULL(CAST(options_json AS CHAR),''), IFNULL(unit,''), is_aggregatable, sort_order) ORDER BY id SEPARATOR '#') "
                + "FROM activity_template_fields WHERE template_id = " + w.template());
        List<String> templateColumns = columns("activity_templates");
        List<String> fieldColumns = columns("activity_template_fields");

        JsonNode current = settings(w.admin(), w.team(), w.template());
        expect(putSettings(w.admin(), w.team(), w.template(), validBody(current)), 200);
        snapshot(w.member(), w.team(), w.template());

        assertThat(activityRow("SELECT CONCAT_WS('|', id, scope_type, scope_id, name, IFNULL(description,''), "
                + "is_participant_required, default_visibility, sort_order, created_by, IFNULL(deleted_at,''), updated_at) "
                + "FROM activity_templates WHERE id = " + w.template())).isEqualTo(templateRow);
        assertThat(activityRow("SELECT GROUP_CONCAT(CONCAT_WS('|', id, field_key, field_label, field_type, is_required, "
                + "IFNULL(CAST(options_json AS CHAR),''), IFNULL(unit,''), is_aggregatable, sort_order) ORDER BY id SEPARATOR '#') "
                + "FROM activity_template_fields WHERE template_id = " + w.template())).isEqualTo(fieldRows);
        assertThat(columns("activity_templates")).isEqualTo(templateColumns);
        assertThat(columns("activity_template_fields")).isEqualTo(fieldColumns)
                .noneMatch(c -> c.contains("semantic") || c.contains("effort") || c.contains("aggregation"));
    }

    // CONCAT_WS/GROUP_CONCAT の結果が binary 型（VARBINARY/BLOB。DATETIME/ENUM 混在の連結や GROUP_CONCAT で起きうる）で返ると byte[] になるため文字列へ復号する
    private String activityRow(String sql) {
        return tx(() -> {
            Object v = em.createNativeQuery(sql).getSingleResult();
            return v instanceof byte[] b ? new String(b, StandardCharsets.UTF_8) : String.valueOf(v);
        });
    }

    private List<String> columns(String table) {
        return tx(() -> em.createNativeQuery("SELECT column_name FROM information_schema.columns "
                        + "WHERE table_schema = DATABASE() AND table_name = :t ORDER BY ordinal_position")
                .setParameter("t", table).getResultList().stream().map(c -> String.valueOf(c).toLowerCase()).toList());
    }

    // ═══════════════════════════════ 集計可否との接続・性能 ═══════════════════════════════

    @Test
    @DisplayName("semantic 境界: NUMBER は aggregation=SUM を設定したときだけ BAR 集計でき、NONE（未設定の既定を含む）は 400")
    void AC21_NUMBERはSUM設定時のみBAR集計可() throws Exception {
        World w = world("数値集計", List.of(FieldSpec.number("f", "距離", 0)));
        LocalDate day = LocalDate.of(2026, 11, 3);

        // 未設定（既定 NUMBER/NONE）の記録は BAR 不可。
        JsonNode snap1 = snapshot(w.member(), w.team(), w.template());
        createTeamRecord(w.member(), snap1, day, Map.of("距離", "3"));
        expectError(http(stats(snap1.path("definition"), day), w.member()), 400, "COMMON_001");

        // 管理者が SUM を設定した版の記録は BAR 可。
        JsonNode current = settings(w.admin(), w.team(), w.template());
        expect(putSettings(w.admin(), w.team(), w.template(), body(current.path("version").asLong(),
                List.of(setting(keyOf(current.path("definition"), "距離"), "NUMBER", "KILOMETER", "SUM")), null, null)), 200);
        JsonNode snap2 = snapshot(w.member(), w.team(), w.template());
        createTeamRecord(w.member(), snap2, day, Map.of("距離", "4"));
        JsonNode result = expect(http(stats(snap2.path("definition"), day), w.member()), 200).path("data");
        assertThat(result.path("semantic").asText()).isEqualTo("NUMBER");
        assertThat(result.path("unit").asText()).isEqualTo("KILOMETER");
        assertThat(result.path("total").asText()).as("KILOMETER/SUM の記録だけを合計（未設定版は別 series）").isEqualTo("4.00");
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder stats(JsonNode definition, LocalDate day) {
        return get(ME + "/stats").param("date_from", day.toString()).param("date_to", day.toString())
                .param("definition_uuid", definition.path("definitionUuid").asText())
                .param("field_key", keyOf(definition, "距離")).param("chart", "BAR");
    }

    @Test
    @DisplayName("AC-21: 15 field の template でも GET の SQL 数は 1 field 時から増えない")
    void AC21_15fieldでもGETのSQL数が増えない() throws Exception {
        World small = world("設定少", List.of(FieldSpec.number("n", "数", 0)));
        List<FieldSpec> fifteen = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            fifteen.add(FieldSpec.number("n" + i, "数" + i, i));
        }
        World large = world("設定多", fifteen);
        // 初回の定義作成を済ませてから測る。
        settings(small.admin(), small.team(), small.template());
        settings(large.admin(), large.team(), large.template());

        int one = statements(small);
        int many = statements(large);
        assertThat(many).as("15 field の SQL 数（1 field 時=" + one + "）").isLessThanOrEqualTo(one);
    }

    private int statements(World w) throws Exception {
        try (SqlIntentCounter.CurrentThreadCapture capture = SqlIntentCounter.captureCurrentThread()) {
            settings(w.admin(), w.team(), w.template());
            return capture.totalCount();
        }
    }
}
