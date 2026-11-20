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
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * F06.4.1 PR-2 チーム定義 snapshot 登録 API（試練 / red 先行。CMP-261115-2145）。AC-19（E02）。
 *
 * <p>{@code POST /api/v1/effort-records/me/definition-snapshots} を実 JWT・実 Security・実 MySQL で叩く。
 * activity テンプレートは既存 {@code ActivityTemplateService}（実 Service）で作り・変え・廃止する（外部境界ではないのでモックしない）。
 * revision と定義 JSON は effort_definition_revisions を再読込して検証する。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("F06.4.1 チーム定義 snapshot 登録（AC-19）")
class EffortDefinitionSnapshotContractIT extends EffortRecordItSupport {

    private static final String OPTIONS = "[\"数学\",\"英語\"]";

    private List<FieldSpec> baseFields() {
        return List.of(
                FieldSpec.select("subject", "科目", OPTIONS, 0),
                FieldSpec.number("minutes", "時間", 1),
                FieldSpec.text("memo", "メモ", 2));
    }

    /** チーム・ADMIN・MEMBER・テンプレートの一式（チームの親組織の形は shape。基本は親組織なし）。 */
    private record World(Long team, Long admin, Long member, Long template) {
    }

    private World world(String name) {
        return world(name, TeamShape.STANDALONE);
    }

    private World world(String name, TeamShape shape) {
        Long team = team(shape, name);
        Long admin = user(name + "管理者");
        teamAdmin(admin, team);
        Long member = user(name + "部員");
        member(member, team);
        Long template = teamTemplate(admin, team, name + "練習", baseFields());
        return new World(team, admin, member, template);
    }

    private long teamDefinitionRows(Long teamId) {
        return count("SELECT COUNT(*) FROM effort_definitions WHERE source_type = 'TEAM' AND team_id = ?1", teamId);
    }

    // ═══════════════════════════════ 正常系 ═══════════════════════════════

    @ParameterizedTest(name = "AC-19: 親組織={0}")
    @EnumSource(TeamShape.class)
    @DisplayName("AC-19: 所属チームの TEAM template は 200・10分有効 token・定義 snapshot を返し、revision 1 をサーバに保存する（親組織0件・2件とも）")
    void AC19_所属チームのTEAMテンプレートは200と10分有効token(TeamShape shape) throws Exception {
        World w = world("登録", shape);
        Instant before = Instant.now();

        JsonNode data = snapshot(w.member(), w.team(), w.template());

        JsonNode definition = data.path("definition");
        assertThat(definition.path("source").asText()).isEqualTo("TEAM");
        assertThat(definition.path("presetCode").isNull()).as("TEAM は presetCode=null").isTrue();
        assertThat(definition.path("teamTemplateId").asLong()).as("既存 BIGINT の templateId").isEqualTo(w.template());
        assertThat(definition.path("templateName").asText()).isEqualTo("登録練習");
        assertThat(definition.path("revision").asInt()).isEqualTo(1);
        String definitionUuid = definition.path("definitionUuid").asText();
        assertThat(UUID.fromString(definitionUuid).version()).as("definitionUuid は UUIDv7").isEqualTo(7);

        // fields は activity の並び順（sortOrder）どおり、label・型・必須・選択肢を写す。labelKey は標準プリセットのみ非 null。
        List<String> labels = new ArrayList<>();
        definition.path("fields").forEach(f -> labels.add(f.path("label").asText()));
        assertThat(labels).containsExactly("科目", "時間", "メモ");
        assertThat(fieldOf(definition, "科目").path("type").asText()).isEqualTo("SELECT");
        assertThat(fieldOf(definition, "時間").path("type").asText()).isEqualTo("NUMBER");
        assertThat(fieldOf(definition, "メモ").path("type").asText()).isEqualTo("TEXT");
        definition.path("fields").forEach(f -> {
            assertThat(f.path("labelKey").isNull()).as("TEAM の labelKey は null: " + f).isTrue();
            assertThat(f.path("fieldKey").asText()).as("fieldKey は契約の形式").matches("[a-z][a-z0-9_]{0,63}");
        });
        List<String> optionLabels = new ArrayList<>();
        fieldOf(definition, "科目").path("options").forEach(o -> optionLabels.add(o.path("label").asText()));
        assertThat(optionLabels).containsExactly("数学", "英語");

        // 指標未設定の既定（API 契約 §7）: 数値は NUMBER として集計対象外、自由 TEXT は aggregation=NONE。duration/subject 対応なし。
        assertThat(fieldOf(definition, "時間").path("semantic").asText()).isEqualTo("NUMBER");
        assertThat(fieldOf(definition, "時間").path("aggregation").asText()).isEqualTo("NONE");
        assertThat(fieldOf(definition, "メモ").path("semantic").asText()).isEqualTo("TEXT");
        assertThat(fieldOf(definition, "メモ").path("aggregation").asText()).isEqualTo("NONE");
        assertThat(definition.path("durationFieldKey").isNull()).isTrue();
        assertThat(definition.path("subjectFieldKey").isNull()).isTrue();

        // token は 10 分有効。
        assertThat(data.path("definitionToken").asText()).isNotBlank();
        Instant expiresAt = Instant.parse(data.path("expiresAt").asText());
        assertThat(expiresAt).isBetween(before.plus(Duration.ofMinutes(10)).minusSeconds(5),
                Instant.now().plus(Duration.ofMinutes(10)).plusSeconds(5));

        // 再読込: TEAM 定義行（team 側。組織 ID は持たない）と revision 1 の定義 JSON が応答と一致する。
        assertThat(teamDefinitionRows(w.team())).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM effort_definitions WHERE id = UNHEX(REPLACE(?1, '-', '')) "
                        + "AND source_type = 'TEAM' AND source_key = ?4 AND team_id = ?2 AND team_template_id = ?3 "
                        + "AND current_revision = 1 AND retired_at IS NULL",
                definitionUuid, w.team(), w.template(), "team:" + w.team() + ":template:" + w.template()))
                .as("source_key / team / template を束縛した定義行").isEqualTo(1);
        assertTeamDefinitionHasNoOrganization(definitionUuid);
        assertThat(revisionCount(definitionUuid)).isEqualTo(1);
        assertThat(storedRevision(definitionUuid, 1)).as("保存 JSON は応答 definition と同一").isEqualTo(definition);

        // 原本は作らない（個人一覧にも出ない）。
        assertThat(count("SELECT COUNT(*) FROM effort_records WHERE owner_user_id = ?1", w.member())).isZero();
    }

    @ParameterizedTest(name = "AC-19: 親組織={0}")
    @EnumSource(TeamShape.class)
    @DisplayName("AC-19: 同じ定義（同 hash）の再登録では revision を増やさない（別の所属者が呼んでも同じ定義・revision）")
    void AC19_同一hashならrevision不変(TeamShape shape) throws Exception {
        World w = world("同一", shape);
        Long another = user("同一部員2");
        member(another, w.team());

        JsonNode first = snapshot(w.member(), w.team(), w.template()).path("definition");
        JsonNode second = snapshot(w.member(), w.team(), w.template()).path("definition");
        JsonNode third = snapshot(another, w.team(), w.template()).path("definition");

        assertThat(second).isEqualTo(first);
        assertThat(third).isEqualTo(first);
        String uuid = first.path("definitionUuid").asText();
        assertThat(revisionCount(uuid)).isEqualTo(1);
        assertThat(teamDefinitionRows(w.team())).as("初回利用時の一意作成（定義行は1件）").isEqualTo(1);
    }

    @ParameterizedTest(name = "AC-19: {0} の変更で revision +1")
    @ValueSource(strings = {"label", "options", "required", "order", "templateName"})
    @DisplayName("AC-19: label / options / required / 順序 / テンプレート名の変更で revision +1、旧 revision は不変")
    void AC19_定義変更でrevisionが1増える(String change) throws Exception {
        World w = world("変更" + change);
        JsonNode rev1 = snapshot(w.member(), w.team(), w.template()).path("definition");
        String uuid = rev1.path("definitionUuid").asText();

        List<FieldSpec> fields = new ArrayList<>(baseFields());
        String name = "変更" + change + "練習";
        switch (change) {
            case "label" -> fields.set(1, fields.get(1).withLabel("練習時間"));
            case "options" -> fields.set(0, fields.get(0).withOptions("[\"数学\",\"英語\",\"理科\"]"));
            case "required" -> fields.set(1, fields.get(1).withRequired(true));
            case "order" -> {
                fields.set(0, fields.get(0).withOrder(2));
                fields.set(2, fields.get(2).withOrder(0));
            }
            case "templateName" -> name = "改名後";
            default -> throw new IllegalArgumentException(change);
        }
        updateTeamTemplate(w.admin(), w.template(), name, fields);

        JsonNode rev2 = snapshot(w.member(), w.team(), w.template()).path("definition");
        assertThat(rev2.path("definitionUuid").asText()).as("同じテンプレートは同じ definitionUuid").isEqualTo(uuid);
        assertThat(rev2.path("revision").asInt()).isEqualTo(2);
        assertThat(rev2).isNotEqualTo(rev1);
        assertThat(revisionCount(uuid)).isEqualTo(2);
        assertThat(storedRevision(uuid, 1)).as("旧 revision の定義 JSON は書き換えない").isEqualTo(rev1);
        assertThat(storedRevision(uuid, 2)).isEqualTo(rev2);
        assertThat(count("SELECT current_revision FROM effort_definitions WHERE id = UNHEX(REPLACE(?1, '-', ''))", uuid))
                .isEqualTo(2);

        // 変更後に同じ定義で再登録しても +1 しない。
        assertThat(snapshot(w.member(), w.team(), w.template()).path("definition").path("revision").asInt()).isEqualTo(2);
        assertThat(revisionCount(uuid)).isEqualTo(2);
    }

    // ═══════════════════════════════ 認可・IDOR ═══════════════════════════════

    @Test
    @DisplayName("AC-19: 非所属・脱退済み・SYSTEM_ADMIN（非所属）は 403。存在しない template でも 403 で存在を漏らさない。定義行を作らない")
    void AC19_非所属は403() throws Exception {
        World w = world("非所属");
        Long outsider = user("部外者");
        member(outsider, team("部外者のチーム"));
        Long sysAdmin = systemAdmin("運営");
        Long leaver = user("脱退者");
        member(leaver, w.team());
        leave(leaver, w.team());

        expectError(postSnapshot(outsider, w.team(), w.template()), 403, "COMMON_002");
        expectError(postSnapshot(outsider, w.team(), Long.MAX_VALUE - 7), 403, "COMMON_002");
        expectError(postSnapshot(sysAdmin, w.team(), w.template()), 403, "COMMON_002");
        expectError(postSnapshot(leaver, w.team(), w.template()), 403, "COMMON_002");
        expect(postSnapshot(null, w.team(), w.template()), 401);

        assertThat(teamDefinitionRows(w.team())).as("拒否された要求は定義行を作らない").isZero();
        // 対照: 所属者なら通る（拒否が API 一律失敗ではないこと）。
        snapshot(w.member(), w.team(), w.template());
    }

    @ParameterizedTest(name = "AC-19: 親組織={0}")
    @EnumSource(TeamShape.class)
    @DisplayName("AC-19: 別チーム（同じ親組織に属していても）・組織スコープ・不在の templateId は 404 EFFORT_001 で拒否し、teamId 側に定義を作らない")
    void AC19_別チームのtemplateIdは拒否(TeamShape shape) throws Exception {
        World a = world("甲", shape);
        // 乙は甲と同じ親組織に加盟させる（親組織0件なら独立チーム同士）。同じ組織に属することは何の許可にもならない。
        Long bTeam = team("乙");
        parentOrganizations(a.team()).forEach(org -> attachToOrganization(bTeam, org));
        Long bAdmin = user("乙管理者");
        teamAdmin(bAdmin, bTeam);
        World b = new World(bTeam, bAdmin, null, teamTemplate(bAdmin, bTeam, "乙練習", baseFields()));
        // 甲・乙の両方に所属する者が、甲の teamId で乙の template を差し替える。
        member(a.member(), b.team());

        expectError(postSnapshot(a.member(), a.team(), b.template()), 404, "EFFORT_001");
        expectError(postSnapshot(a.member(), a.team(), Long.MAX_VALUE - 7), 404, "EFFORT_001");

        // 組織スコープの activity テンプレート（scopeType=ORGANIZATION）も TEAM template として受けない
        // （甲の親組織のテンプレートでも、親組織が無ければ無関係な組織のテンプレートでも同じ）。
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
            expectError(postSnapshot(a.member(), a.team(), orgTemplate), 404, "EFFORT_001");
        } finally {
            tx(() -> {
                em.createNativeQuery("DELETE FROM activity_template_fields WHERE template_id = :id")
                        .setParameter("id", orgTemplate).executeUpdate();
                em.createNativeQuery("DELETE FROM activity_templates WHERE id = :id").setParameter("id", orgTemplate)
                        .executeUpdate();
                em.createNativeQuery("DELETE FROM memberships WHERE user_id = :u").setParameter("u", orgAdmin).executeUpdate();
                return null;
            });
        }

        assertThat(teamDefinitionRows(a.team())).isZero();
        assertThat(count("SELECT COUNT(*) FROM effort_definitions WHERE team_template_id = ?1", b.template()))
                .as("差し替え要求で乙の template の定義も作らない").isZero();
        // 対照: 正しい組なら通る。
        snapshot(a.member(), b.team(), b.template());
    }

    // ═══════════════════════════════ 廃止 ═══════════════════════════════

    @Test
    @DisplayName("AC-19: 登録済み template を廃止すると 409 EFFORT_002、revision を増やさない")
    void AC19_廃止templateは409() throws Exception {
        World w = world("廃止");
        String uuid = snapshot(w.member(), w.team(), w.template()).path("definition").path("definitionUuid").asText();

        retireTeamTemplate(w.admin(), w.template());

        expectError(postSnapshot(w.member(), w.team(), w.template()), 409, "EFFORT_002");
        assertThat(revisionCount(uuid)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM effort_definition_revisions WHERE definition_id = UNHEX(REPLACE(?1, '-', ''))",
                uuid)).as("廃止後も revision を物理削除しない").isEqualTo(1);
    }

    // ═══════════════════════════════ 入力検証（空・null・境界） ═══════════════════════════════

    @Test
    @DisplayName("AC-19: teamId / teamTemplateId の欠落・null・型不一致・未知プロパティは 400")
    void AC19_入力検証() throws Exception {
        World w = world("検証");
        Map<String, Object> missingTeam = new LinkedHashMap<>();
        missingTeam.put("teamTemplateId", w.template());
        expectError(postSnapshot(w.member(), missingTeam), 400, "COMMON_001");

        Map<String, Object> nullTemplate = new LinkedHashMap<>();
        nullTemplate.put("teamId", w.team());
        nullTemplate.put("teamTemplateId", null);
        expectError(postSnapshot(w.member(), nullTemplate), 400, "COMMON_001");

        Map<String, Object> wrongType = new LinkedHashMap<>();
        wrongType.put("teamId", "abc");
        wrongType.put("teamTemplateId", w.template());
        expectError(postSnapshot(w.member(), wrongType), 400, "COMMON_001");

        Map<String, Object> unknown = new LinkedHashMap<>();
        unknown.put("teamId", w.team());
        unknown.put("teamTemplateId", w.template());
        unknown.put("definition", Map.of("fields", List.of()));
        expectError(postSnapshot(w.member(), unknown), 400, "COMMON_001");

        expectError(http(post(SNAPSHOTS).contentType(MediaType.APPLICATION_JSON).content(""), w.member()), 400, "COMMON_001");

        assertThat(teamDefinitionRows(w.team())).isZero();
        // 対照: 正しい本文なら通る。
        snapshot(w.member(), w.team(), w.template());
    }

    // ═══════════════════════════════ 並行・性能 ═══════════════════════════════

    @Test
    @DisplayName("AC-19: 初回利用の同時登録でも定義行・revision は各1件（一意作成）")
    void AC19_初回同時登録でも定義は1件() throws Exception {
        World w = world("同時");
        Long another = user("同時部員2");
        member(another, w.team());

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<MockHttpServletResponse>> futures = new ArrayList<>();
            for (Long actor : List.of(w.member(), another)) {
                Callable<MockHttpServletResponse> call = () -> {
                    start.await();
                    return postSnapshot(actor, w.team(), w.template());
                };
                futures.add(pool.submit(call));
            }
            start.countDown();
            List<String> uuids = new ArrayList<>();
            for (Future<MockHttpServletResponse> future : futures) {
                uuids.add(expect(future.get(), 200).at("/data/definition/definitionUuid").asText());
            }
            assertThat(uuids.get(0)).isEqualTo(uuids.get(1));
            assertThat(teamDefinitionRows(w.team())).isEqualTo(1);
            assertThat(revisionCount(uuids.get(0))).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("AC-19: 15 field の template でも SQL 数は 1 field 時から増えない（field ごとの照会なし）")
    void AC19_15fieldでもSQL数が増えない() throws Exception {
        World small = world("少");
        World large = world("多");
        List<FieldSpec> fifteen = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            fifteen.add(FieldSpec.number("n" + i, "数" + i, i));
        }
        Long bigTemplate = teamTemplate(large.admin(), large.team(), "多い練習", fifteen);
        Long oneTemplate = teamTemplate(small.admin(), small.team(), "少ない練習", List.of(FieldSpec.number("n", "数", 0)));

        int one = statements(small.member(), small.team(), oneTemplate);
        int many = statements(large.member(), large.team(), bigTemplate);
        assertThat(many).as("15 field の SQL 数（1 field 時=" + one + "）").isLessThanOrEqualTo(one);
    }

    private int statements(Long actor, Long team, Long template) throws Exception {
        try (SqlIntentCounter.CurrentThreadCapture capture = SqlIntentCounter.captureCurrentThread()) {
            expect(postSnapshot(actor, team, template), 200);
            return capture.totalCount();
        }
    }
}
