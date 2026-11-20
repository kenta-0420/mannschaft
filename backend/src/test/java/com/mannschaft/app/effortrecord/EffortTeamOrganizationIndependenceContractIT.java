package com.mannschaft.app.effortrecord;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * F06.4.1 PR-2 チーム側の組織非依存（試練 / red 先行。CMP-261115-2145）。AC-67。
 *
 * <p>マスター裁定（2026-11-18）: 「この機能はそもそもチーム内で共有することが目的なのに、なんで組織に所属していることが前提なの？」。
 * チーム側（TEAM 定義・definitionToken・指標設定）は組織に依存させない。親組織 0 件のチームも複数の親組織に属するチームも、
 * definition-snapshots / TEAM 原本作成 / template-settings でまったく同じ結果になり、保存される TEAM 定義に組織 ID が入らない。
 * 親組織への加盟・離脱は判定に使われない（発行済み token も定義 revision も変わらない）。</p>
 *
 * <p>比較は ID（definitionUuid・teamTemplateId・不透明な fieldKey）を label に置き換えた正規形で行う。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("F06.4.1 チーム側の組織非依存（AC-67）")
class EffortTeamOrganizationIndependenceContractIT extends EffortRecordItSupport {

    private static final LocalDate DAY = LocalDate.of(2026, 11, 6);
    private static final String TEMPLATE_NAME = "共通練習";

    private record World(Long team, Long admin, Long member, Long template) {
    }

    private static List<FieldSpec> fields() {
        return List.of(
                FieldSpec.select("subject", "科目", "[\"数学\",\"英語\"]", 0),
                FieldSpec.number("minutes", "時間", 1),
                FieldSpec.text("memo", "メモ", 2),
                FieldSpec.number("distance", "距離", 3));
    }

    private World world(String name, TeamShape shape) {
        Long team = team(shape, name);
        Long admin = user(name + "管理者");
        teamAdmin(admin, team);
        Long member = user(name + "部員");
        member(member, team);
        return new World(team, admin, member, teamTemplate(admin, team, TEMPLATE_NAME, fields()));
    }

    private static String settingsUrl(Long team, Long template) {
        return "/api/v1/teams/" + team + "/effort-records/template-settings/" + template;
    }

    private MockHttpServletResponse getSettings(Long actor, Long team, Long template) throws Exception {
        return http(get(settingsUrl(team, template)), actor);
    }

    private MockHttpServletResponse putSettings(Long actor, Long team, Long template, Object body) throws Exception {
        return http(put(settingsUrl(team, template)).contentType(MediaType.APPLICATION_JSON).content(write(body)), actor);
    }

    private static Map<String, Object> setting(String fieldKey, String semantic, String unit, String aggregation) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("fieldKey", fieldKey);
        s.put("semantic", semantic);
        s.put("unit", unit);
        s.put("aggregation", aggregation);
        return s;
    }

    /** 時間=DURATION、科目=SUBJECT、メモ=MENU、距離=NUMBER/KILOMETER/SUM。 */
    private static Map<String, Object> validBody(JsonNode current) {
        JsonNode d = current.path("definition");
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("version", current.path("version").asLong());
        b.put("fields", List.of(
                setting(keyOf(d, "科目"), "SUBJECT", "NONE", "NONE"),
                setting(keyOf(d, "時間"), "DURATION", "MINUTE", "SUM"),
                setting(keyOf(d, "メモ"), "MENU", "NONE", "NONE"),
                setting(keyOf(d, "距離"), "NUMBER", "KILOMETER", "SUM")));
        b.put("durationFieldKey", keyOf(d, "時間"));
        b.put("subjectFieldKey", keyOf(d, "科目"));
        return b;
    }

    /** ID（definitionUuid・teamTemplateId・fieldKey・option key）を除き、対応先は label で表した定義の正規形。 */
    private static String canonical(JsonNode definition) {
        Map<String, String> labelByKey = new LinkedHashMap<>();
        definition.path("fields").forEach(f -> labelByKey.put(f.path("fieldKey").asText(), f.path("label").asText()));
        StringBuilder sb = new StringBuilder();
        sb.append(definition.path("source").asText()).append('|')
                .append(definition.path("presetCode")).append('|')
                .append(definition.path("templateName").asText()).append('|')
                .append(definition.path("revision").asInt()).append('|')
                .append("duration=").append(labelByKey.get(definition.path("durationFieldKey").asText(null))).append('|')
                .append("subject=").append(labelByKey.get(definition.path("subjectFieldKey").asText(null)));
        for (JsonNode f : definition.path("fields")) {
            List<String> options = new ArrayList<>();
            f.path("options").forEach(o -> options.add(o.path("label").asText()));
            sb.append("\n").append(f.path("label").asText()).append(',').append(f.path("type").asText()).append(',')
                    .append(f.path("semantic").asText()).append(',').append(f.path("unit").asText()).append(',')
                    .append(f.path("aggregation").asText()).append(',').append(f.path("required")).append(',')
                    .append(f.path("scale")).append(',').append(f.path("min")).append(',').append(f.path("max")).append(',')
                    .append(f.path("labelKey")).append(',').append(options);
        }
        return sb.toString();
    }

    /** 1 チーム分の流れ（指標設定 GET→PUT→部員の snapshot→TEAM 原本作成）の結果を正規形で集める。 */
    private List<String> runFlow(World w) throws Exception {
        List<String> observed = new ArrayList<>();
        JsonNode before = expect(getSettings(w.admin(), w.team(), w.template()), 200).path("data");
        observed.add("settings.get=" + canonical(before.path("definition")));
        JsonNode updated = expect(putSettings(w.admin(), w.team(), w.template(), validBody(before)), 200).path("data");
        observed.add("settings.put=" + canonical(updated.path("definition"))
                + "|versionDelta=" + (updated.path("version").asLong() - before.path("version").asLong()));

        Instant issuedAfter = Instant.now();
        JsonNode snap = snapshot(w.member(), w.team(), w.template());
        JsonNode definition = snap.path("definition");
        observed.add("snapshot=" + canonical(definition));
        assertThat(Instant.parse(snap.path("expiresAt").asText()))
                .isBetween(issuedAfter.plus(Duration.ofMinutes(10)).minusSeconds(5), Instant.now().plus(Duration.ofMinutes(10)).plusSeconds(5));
        assertThat(definition.path("teamTemplateId").asLong()).isEqualTo(w.template());

        String subject = fieldOf(definition, "科目").path("options").get(0).path("key").asText();
        JsonNode created = createTeamRecord(w.member(), snap, DAY, Map.of("科目", subject, "時間", "30", "メモ", "腕立て", "距離", "5"));
        observed.add("created=" + canonical(created.path("definition")) + "|version=" + created.path("version").asLong()
                + "|time=" + valueOf(created, keyOf(definition, "時間")).asText()
                + "|distance=" + valueOf(created, keyOf(definition, "距離")).asText());

        assertTeamDefinitionHasNoOrganization(definition.path("definitionUuid").asText());
        assertThat(count("SELECT COUNT(*) FROM effort_definitions WHERE source_type = 'TEAM' AND team_id = ?1", w.team()))
                .as("チームごとに定義は1件").isEqualTo(1);
        return observed;
    }

    @Test
    @DisplayName("AC-67: 親組織0件のチームと2件のチームで、指標設定・definition-snapshots・TEAM 原本作成が同じ結果になり、定義に組織 ID が入らない")
    void AC67_親組織0件と2件で同じ結果() throws Exception {
        World standalone = world("独立", TeamShape.STANDALONE);
        World twoParents = world("加盟", TeamShape.TWO_PARENT_ORGS);
        assertThat(parentOrganizations(standalone.team())).isEmpty();
        assertThat(parentOrganizations(twoParents.team())).hasSize(2);

        assertThat(runFlow(standalone)).isEqualTo(runFlow(twoParents));
    }

    @Test
    @DisplayName("AC-67: 拒否の結果も親組織の有無で変わらない（非所属 403・別チーム template 404 EFFORT_001・同じ親組織の別チームでも 404）")
    void AC67_拒否結果も親組織で変わらない() throws Exception {
        World standalone = world("独立拒否", TeamShape.STANDALONE);
        World twoParents = world("加盟拒否", TeamShape.TWO_PARENT_ORGS);
        // 加盟チームと同じ2組織に属する別チーム（その template を加盟チームの path で使わせる）。
        World sibling = world("兄弟", TeamShape.STANDALONE);
        parentOrganizations(twoParents.team()).forEach(org -> attachToOrganization(sibling.team(), org));
        Long outsider = user("部外者");
        member(outsider, team("部外者のチーム"));

        for (World w : List.of(standalone, twoParents)) {
            expectError(postSnapshot(outsider, w.team(), w.template()), 403, "COMMON_002");
            expectError(getSettings(outsider, w.team(), w.template()), 403, "COMMON_002");
            expectError(getSettings(w.member(), w.team(), w.template()), 403, "COMMON_002");
        }
        // 別チームの template: 独立同士でも、同じ親組織に属するチーム同士でも 404。
        member(standalone.member(), sibling.team());
        member(twoParents.member(), sibling.team());
        expectError(postSnapshot(standalone.member(), standalone.team(), sibling.template()), 404, "EFFORT_001");
        expectError(postSnapshot(twoParents.member(), twoParents.team(), sibling.template()), 404, "EFFORT_001");
        expectError(getSettings(standalone.admin(), standalone.team(), sibling.template()), 404, "EFFORT_001");
        expectError(getSettings(twoParents.admin(), twoParents.team(), sibling.template()), 404, "EFFORT_001");

        assertThat(count("SELECT COUNT(*) FROM effort_definitions WHERE team_template_id = ?1", sibling.template()))
                .as("拒否された差し替えで定義を作らない").isZero();
        // 対照: 正しい組なら両方とも通る（拒否が API 一律失敗ではないこと）。
        snapshot(standalone.member(), standalone.team(), standalone.template());
        snapshot(twoParents.member(), twoParents.team(), twoParents.template());
    }

    @Test
    @DisplayName("AC-67: 親組織への加盟・離脱は判定に使われない（定義 revision は増えず、発行済み token でそのまま作成できる）")
    void AC67_親組織の加盟離脱は判定に使われない() throws Exception {
        World w = world("移籍", TeamShape.STANDALONE);
        JsonNode beforeJoin = snapshot(w.member(), w.team(), w.template());
        String uuid = beforeJoin.at("/definition/definitionUuid").asText();

        // 2組織へ加盟しても同じ定義・同じ revision、加盟前に発行した token で作成できる。
        Long org1 = organization("加盟先1");
        Long org2 = organization("加盟先2");
        attachToOrganization(w.team(), org1);
        attachToOrganization(w.team(), org2);
        JsonNode afterJoin = snapshot(w.member(), w.team(), w.template());
        assertThat(afterJoin.path("definition")).as("加盟で定義は変わらない").isEqualTo(beforeJoin.path("definition"));
        createTeamRecord(w.member(), beforeJoin, DAY, Map.of("時間", "10"));

        // 全組織から離脱しても同じ。加盟中に発行した token で作成できる。
        detachFromAllOrganizations(w.team());
        JsonNode afterLeave = snapshot(w.member(), w.team(), w.template());
        assertThat(afterLeave.path("definition")).as("離脱で定義は変わらない").isEqualTo(beforeJoin.path("definition"));
        createTeamRecord(w.member(), afterJoin, DAY, Map.of("時間", "20"));

        assertThat(revisionCount(uuid)).isEqualTo(1);
        assertTeamDefinitionHasNoOrganization(uuid);
        assertThat(count("SELECT COUNT(*) FROM effort_records WHERE owner_user_id = ?1", w.member())).isEqualTo(2);
    }

    @Test
    @DisplayName("AC-67: 親組織を持たない複数のチームに所属する本人は、各チームの定義で別々に記録できる")
    void AC67_複数の独立チームでそれぞれ使える() throws Exception {
        Long owner = user("掛け持ち");
        Set<String> uuids = new HashSet<>();
        for (int i = 1; i <= 3; i++) {
            Long team = team("独立チーム" + i);
            Long admin = user("独立管理者" + i);
            teamAdmin(admin, team);
            member(owner, team);
            Long template = teamTemplate(admin, team, TEMPLATE_NAME, fields());

            JsonNode snap = snapshot(owner, team, template);
            String uuid = snap.at("/definition/definitionUuid").asText();
            uuids.add(uuid);
            createTeamRecord(owner, snap, DAY, Map.of("時間", String.valueOf(10 * i)));
            assertTeamDefinitionHasNoOrganization(uuid);
            assertThat(count("SELECT COUNT(*) FROM effort_definitions WHERE source_type = 'TEAM' AND team_id = ?1", team))
                    .isEqualTo(1);
        }
        assertThat(uuids).as("チームごとに別の定義").hasSize(3);
        assertThat(count("SELECT COUNT(*) FROM effort_records WHERE owner_user_id = ?1", owner)).isEqualTo(3);
    }
}
