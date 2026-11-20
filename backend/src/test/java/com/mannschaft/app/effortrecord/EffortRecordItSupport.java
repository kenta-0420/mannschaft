package com.mannschaft.app.effortrecord;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.activity.ActivityScopeType;
import com.mannschaft.app.activity.dto.CreateTemplateRequest;
import com.mannschaft.app.activity.dto.UpdateTemplateRequest;
import com.mannschaft.app.activity.service.ActivityTemplateService;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.effortrecord.entity.EffortRecordEntity;
import com.mannschaft.app.effortrecord.repository.EffortRecordRepository;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.organization.entity.OrganizationEntity;
import com.mannschaft.app.organization.repository.OrganizationRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.MembershipTestHelper;
import com.mannschaft.app.team.entity.TeamEntity;
import com.mannschaft.app.team.repository.TeamRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * F06.4.1 個人の頑張り記録 PR-1 試練（CMP-261115-2145）の共通土台。
 *
 * <p><b>方針</b>（TEST_CONVENTION.md §3.1.2）: 共通 {@link AbstractMySqlIntegrationTest} の MOCK 構成に
 * {@link AutoConfigureMockMvc} を重ね、<b>実 JWT・実 Security フィルタ・実 Controller・実 Service・実 MySQL</b> を通す
 * （{@code addFilters=false} は使わない）。fixture と HTTP の書込は commit し、テスト全体の rollback による
 * 偽の緑を避ける。後始末は本土台が作成したユーザーに属する effort 行・所属・チームだけに限定する。</p>
 *
 * <p><b>標準プリセット</b>: test profile は Flyway 無効（ddl-auto=create）のため、V260 migration の
 * プリセット seed が入らない。そこで<b>migration ファイル自身の seed 節</b>（{@code -- 標準プリセット定義 seed} 以降）を
 * 読み出して 1 度だけ実行する。プリセットの内容（定義 JSON・hash）は migration を単一の正本とし、試験側に複製しない。
 * ただし effort_definitions 行だけは V261 の列（organization_id。V264 で削除）が現行スキーマに無いため、
 * V264 適用後相当の列で試験側が入れる（適用済み migration は書き換えない）。</p>
 */
@AutoConfigureMockMvc
abstract class EffortRecordItSupport extends AbstractMySqlIntegrationTest {

    static final UUID STUDY = UUID.fromString("01938a00-0000-7000-8000-000000000001");
    static final UUID EXERCISE = UUID.fromString("01938a00-0000-7000-8000-000000000002");
    static final UUID WEIGHT = UUID.fromString("01938a00-0000-7000-8000-000000000003");

    static final String ME = "/api/v1/effort-records/me";
    static final String TOKYO = "Asia/Tokyo";

    @Autowired protected MockMvc mvc;
    @Autowired protected ObjectMapper mapper;
    @Autowired protected AuthTokenService tokens;
    @Autowired protected PlatformTransactionManager txManager;
    @Autowired protected UserRepository users;
    @Autowired protected TeamRepository teams;
    @Autowired protected EffortRecordRepository records;
    @Autowired protected DataSource dataSource;
    @Autowired protected ActivityTemplateService activityTemplates;
    @Autowired protected OrganizationRepository organizations;
    @PersistenceContext protected EntityManager em;

    /** 本土台で作成したユーザー（後始末対象）。 */
    protected final List<Long> ownedUsers = new ArrayList<>();
    /** 本土台で作成したチーム（後始末対象）。 */
    protected final List<Long> ownedTeams = new ArrayList<>();
    /** 本土台で作成した組織（後始末対象。PR-2 の親組織・組織テンプレート fixture 用）。 */
    protected final List<Long> ownedOrganizations = new ArrayList<>();
    /** SYSTEM_ADMIN ロールでトークンを発行するユーザー。 */
    protected final List<Long> systemAdmins = new ArrayList<>();

    @BeforeEach
    void effortプリセットseedをmigrationから投入する() throws Exception {
        seedPresetsFromMigration(dataSource);
    }

    @AfterEach
    void 所有fixtureだけ片付ける() {
        SecurityContextHolder.clearContext();
        if (ownedUsers.isEmpty() && ownedTeams.isEmpty() && ownedOrganizations.isEmpty()) {
            return;
        }
        tx(() -> {
            for (Long id : ownedUsers) {
                em.createNativeQuery("DELETE FROM effort_records WHERE owner_user_id = :id").setParameter("id", id).executeUpdate();
                em.createNativeQuery("DELETE FROM effort_operation_receipts WHERE actor_user_id = :id").setParameter("id", id).executeUpdate();
                em.createNativeQuery("DELETE FROM user_roles WHERE user_id = :id").setParameter("id", id).executeUpdate();
                em.createNativeQuery("DELETE FROM memberships WHERE user_id = :id").setParameter("id", id).executeUpdate();
                em.createNativeQuery("DELETE FROM audit_logs WHERE user_id = :id").setParameter("id", id).executeUpdate();
            }
            for (Long id : ownedTeams) {
                // PR-2: チーム定義（effort 側）→ activity テンプレート → 組織所属の順に、本土台のチームに属する行だけ消す。
                em.createNativeQuery("DELETE r FROM effort_definition_revisions r JOIN effort_definitions d ON d.id = r.definition_id "
                        + "WHERE d.source_type = 'TEAM' AND d.team_id = :id").setParameter("id", id).executeUpdate();
                em.createNativeQuery("DELETE FROM effort_definitions WHERE source_type = 'TEAM' AND team_id = :id")
                        .setParameter("id", id).executeUpdate();
                em.createNativeQuery("DELETE f FROM activity_template_fields f JOIN activity_templates t ON t.id = f.template_id "
                        + "WHERE t.scope_type = 'TEAM' AND t.scope_id = :id").setParameter("id", id).executeUpdate();
                em.createNativeQuery("DELETE FROM activity_templates WHERE scope_type = 'TEAM' AND scope_id = :id")
                        .setParameter("id", id).executeUpdate();
                em.createNativeQuery("DELETE FROM team_org_memberships WHERE team_id = :id").setParameter("id", id).executeUpdate();
                em.createNativeQuery("DELETE FROM teams WHERE id = :id").setParameter("id", id).executeUpdate();
            }
            for (Long id : ownedOrganizations) {
                em.createNativeQuery("DELETE FROM organizations WHERE id = :id").setParameter("id", id).executeUpdate();
            }
            for (Long id : ownedUsers) {
                em.createNativeQuery("DELETE FROM users WHERE id = :id").setParameter("id", id).executeUpdate();
            }
            return null;
        });
        ownedUsers.clear();
        ownedTeams.clear();
        ownedOrganizations.clear();
        systemAdmins.clear();
    }

    // ───────────────────────────── fixture ─────────────────────────────

    protected Long user(String label) {
        return user(label, TOKYO);
    }

    /** 本人 TZ（users.timezone）を指定してユーザーを作る。未来日判定は本人 TZ を使う（親設計 §3.1）。 */
    protected Long user(String label, String timezone) {
        Long id = tx(() -> users.save(UserEntity.builder()
                .email("effort-" + UUID.randomUUID() + "@example.com")
                .lastName("頑張り").firstName(label).displayName(label).isSearchable(false)
                .status(UserEntity.UserStatus.ACTIVE).locale("ja").timezone(timezone).build()).getId());
        ownedUsers.add(id);
        return id;
    }

    protected Long systemAdmin(String label) {
        Long id = user(label);
        systemAdmins.add(id);
        return id;
    }

    protected Long team(String name) {
        Long id = tx(() -> teams.save(TeamEntity.builder()
                .slug("effort-" + UUID.randomUUID().toString().substring(0, 8)).name(name)
                .visibility(TeamEntity.Visibility.PUBLIC).supporterEnabled(false).build()).getId());
        ownedTeams.add(id);
        return id;
    }

    protected void member(Long userId, Long teamId) {
        tx(() -> {
            MembershipTestHelper.insertMembership(em, userId, ScopeType.TEAM, teamId, RoleKind.MEMBER);
            return null;
        });
    }

    protected void teamAdmin(Long userId, Long teamId) {
        tx(() -> {
            MembershipTestHelper.insertMembership(em, userId, ScopeType.TEAM, teamId, RoleKind.MEMBER);
            MembershipTestHelper.insertUserRole(em, userId, "ADMIN", teamId, null);
            return null;
        });
    }

    /** DEPUTY_ADMIN（既存テンプレート管理権限を委任された副管理者）として所属させる。 */
    protected void teamDeputy(Long userId, Long teamId) {
        tx(() -> {
            MembershipTestHelper.insertMembership(em, userId, ScopeType.TEAM, teamId, RoleKind.MEMBER);
            MembershipTestHelper.insertUserRole(em, userId, "DEPUTY_ADMIN", teamId, null);
            return null;
        });
    }

    /** 所属を終了する（自己脱退相当。left_at を立てる）。 */
    protected void leave(Long userId, Long teamId) {
        tx(() -> em.createNativeQuery("UPDATE memberships SET left_at = UTC_TIMESTAMP() "
                        + "WHERE user_id = :u AND scope_type = 'TEAM' AND scope_id = :t AND left_at IS NULL")
                .setParameter("u", userId).setParameter("t", teamId).executeUpdate());
    }

    // ─────────────────────── PR-2: チーム（親組織の形）・チームテンプレート fixture ───────────────────────

    /**
     * チームの親組織の形。マスター裁定（2026-11-18）: この機能はチーム内共有が目的で、チーム側は組織に依存させない。
     * 親組織 0 件のチームも複数の親組織に属するチームも、まったく同じ結果にならなければならない。
     * 主要成功系はこの2形でパラメタ化して通す。
     */
    protected enum TeamShape {
        /** 親組織なしの独立チーム（基本形）。 */
        STANDALONE(0),
        /** 2つの組織に ACTIVE で加盟しているチーム。 */
        TWO_PARENT_ORGS(2);

        final int parents;

        TeamShape(int parents) {
            this.parents = parents;
        }
    }

    /** 指定の形のチームを作る（TWO_PARENT_ORGS は新しい組織を2つ作って ACTIVE 加盟させる）。 */
    protected Long team(TeamShape shape, String name) {
        Long teamId = team(name);
        for (int i = 0; i < shape.parents; i++) {
            attachToOrganization(teamId, organization(name + "親組織" + (i + 1)));
        }
        return teamId;
    }

    /** 組織を作る（組織テンプレート・親組織の fixture 用。チーム側の判定には使わせない）。 */
    protected Long organization(String name) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Long id = tx(() -> organizations.saveAndFlush(OrganizationEntity.builder().name(name)
                .slug("effort-org-" + suffix).orgType(OrganizationEntity.OrgType.OTHER)
                .visibility(OrganizationEntity.Visibility.PUBLIC)
                .hierarchyVisibility(OrganizationEntity.HierarchyVisibility.NONE)
                .supporterEnabled(false).build()).getId());
        ownedOrganizations.add(id);
        return id;
    }

    /** チームを組織へ ACTIVE で加盟させる（team_org_memberships）。 */
    protected void attachToOrganization(Long teamId, Long organizationId) {
        tx(() -> em.createNativeQuery("INSERT INTO team_org_memberships (team_id, organization_id, status, invited_at, created_at) "
                        + "VALUES (:t, :o, 'ACTIVE', NOW(), NOW())")
                .setParameter("t", teamId).setParameter("o", organizationId).executeUpdate());
    }

    /** チームの組織加盟をすべて外す（親組織 0 件へ戻す）。 */
    protected void detachFromAllOrganizations(Long teamId) {
        tx(() -> em.createNativeQuery("DELETE FROM team_org_memberships WHERE team_id = :t")
                .setParameter("t", teamId).executeUpdate());
    }

    /** チームの ACTIVE な親組織 ID（加盟 ID 昇順）。 */
    protected List<Long> parentOrganizations(Long teamId) {
        return tx(() -> em.createNativeQuery("SELECT organization_id FROM team_org_memberships "
                        + "WHERE team_id = :t AND status = 'ACTIVE' ORDER BY id")
                .setParameter("t", teamId).getResultList().stream().map(o -> ((Number) o).longValue()).toList());
    }

    /**
     * 保存された TEAM 定義行に組織 ID が入っていないこと（データ設計 §2・2026-11-18 裁定）。
     * 設計どおり列自体が削除済みなら列の不在で満たし、列が残っている間は NULL であることを求める。
     * 列の有無そのものは migration 試験（FlywayEffortRecordsTablesMigrationTest）が固定する。
     */
    protected void assertTeamDefinitionHasNoOrganization(String definitionUuid) {
        long column = count("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() "
                + "AND table_name = 'effort_definitions' AND column_name = 'organization_id'");
        assertThat(count("SELECT COUNT(*) FROM effort_definitions WHERE id = UNHEX(REPLACE(?1, '-', '')) AND source_type = 'TEAM'",
                definitionUuid)).as("TEAM 定義行がある").isEqualTo(1);
        if (column > 0) {
            assertThat(count("SELECT COUNT(*) FROM effort_definitions WHERE id = UNHEX(REPLACE(?1, '-', '')) "
                    + "AND organization_id IS NULL", definitionUuid)).as("TEAM 定義に組織 ID を保存しない").isEqualTo(1);
        }
    }

    /** activity テンプレートの field 指定（activity 側の field_key / 表示名 / 型 / 必須 / 選択肢 JSON / 並び順）。 */
    protected record FieldSpec(String key, String label, String type, boolean required, String optionsJson, int sortOrder) {
        static FieldSpec number(String key, String label, int order) {
            return new FieldSpec(key, label, "NUMBER", false, null, order);
        }

        static FieldSpec text(String key, String label, int order) {
            return new FieldSpec(key, label, "TEXT", false, null, order);
        }

        static FieldSpec select(String key, String label, String optionsJson, int order) {
            return new FieldSpec(key, label, "SELECT", false, optionsJson, order);
        }

        static FieldSpec of(String key, String label, String type, int order) {
            return new FieldSpec(key, label, type, false, null, order);
        }

        FieldSpec withLabel(String newLabel) {
            return new FieldSpec(key, newLabel, type, required, optionsJson, sortOrder);
        }

        FieldSpec withRequired(boolean newRequired) {
            return new FieldSpec(key, label, type, newRequired, optionsJson, sortOrder);
        }

        FieldSpec withOptions(String newOptions) {
            return new FieldSpec(key, label, type, required, newOptions, sortOrder);
        }

        FieldSpec withOrder(int newOrder) {
            return new FieldSpec(key, label, type, required, optionsJson, newOrder);
        }

        FieldSpec withType(String newType) {
            return new FieldSpec(key, label, newType, required, "SELECT".equals(newType) ? optionsJson : null, sortOrder);
        }
    }

    private static List<CreateTemplateRequest.TemplateFieldInput> inputs(List<FieldSpec> fields) {
        return fields.stream().map(f -> new CreateTemplateRequest.TemplateFieldInput(f.key(), f.label(), f.type(),
                f.required(), f.optionsJson(), null, null, false, f.sortOrder())).toList();
    }

    /** 既存 activity Service（実 Service・実 DB）で TEAM テンプレートを作る。adminId はそのチームの ADMIN。 */
    protected Long teamTemplate(Long adminId, Long teamId, String name, List<FieldSpec> fields) {
        return activityTemplates.createTemplate(adminId, ActivityScopeType.TEAM, teamId,
                new CreateTemplateRequest(name, null, null, null, null, null, inputs(fields))).getId();
    }

    /** 既存 activity Service でテンプレートを全置換更新する（改名・field 削除/追加・label/必須/選択肢/順序変更）。 */
    protected void updateTeamTemplate(Long adminId, Long templateId, String name, List<FieldSpec> fields) {
        activityTemplates.updateTemplate(templateId, adminId,
                new UpdateTemplateRequest(name, null, null, null, null, null, inputs(fields)));
    }

    /** 既存 activity Service でテンプレートを廃止（論理削除）する。 */
    protected void retireTeamTemplate(Long adminId, Long templateId) {
        activityTemplates.deleteTemplate(templateId, adminId);
    }

    protected static final String SNAPSHOTS = ME + "/definition-snapshots";

    protected MockHttpServletResponse postSnapshot(Long actor, Object body) throws Exception {
        return http(post(SNAPSHOTS).contentType(MediaType.APPLICATION_JSON).content(write(body)), actor);
    }

    protected MockHttpServletResponse postSnapshot(Long actor, Long teamId, Long templateId) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("teamId", teamId);
        body.put("teamTemplateId", templateId);
        return postSnapshot(actor, body);
    }

    /** definition-snapshots を 200 で呼び、data（definition / definitionToken / expiresAt）を返す。 */
    protected JsonNode snapshot(Long actor, Long teamId, Long templateId) throws Exception {
        return expect(postSnapshot(actor, teamId, templateId), 200).path("data");
    }

    /** 定義 snapshot の fields から表示名 label の fieldKey を引く（TEAM の fieldKey は effort 側の発番で不透明）。 */
    protected static String keyOf(JsonNode definition, String label) {
        return fieldOf(definition, label).path("fieldKey").asText();
    }

    /** 定義 snapshot の fields から表示名 label の field を引く。 */
    protected static JsonNode fieldOf(JsonNode definition, String label) {
        for (JsonNode field : definition.path("fields")) {
            if (label.equals(field.path("label").asText())) {
                return field;
            }
        }
        throw new AssertionError("definition.fields に label=" + label + " が無い: " + definition);
    }

    /**
     * TEAM 定義の作成リクエスト本文。values は label→値で受け、snapshot の全 field を（未指定は null で）並べる。
     * definitionUuid / definitionRevision / definitionToken は snapshot API の応答 data をそのまま使う。
     */
    protected Map<String, Object> teamCreateBody(JsonNode snapshotData, LocalDate recordedOn,
                                                 Map<String, String> valuesByLabel, String privateNote) {
        JsonNode definition = snapshotData.path("definition");
        Map<String, String> byKey = new LinkedHashMap<>();
        for (JsonNode field : definition.path("fields")) {
            byKey.put(field.path("fieldKey").asText(), valuesByLabel.get(field.path("label").asText()));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("recordedOn", recordedOn.toString());
        body.put("recordTimezone", TOKYO);
        body.put("definitionUuid", definition.path("definitionUuid").asText());
        body.put("definitionRevision", definition.path("revision").asInt());
        body.put("definitionToken", snapshotData.path("definitionToken").asText());
        body.put("values", valueList(byKey));
        body.put("privateNote", privateNote);
        return body;
    }

    /** TEAM 定義で原本を作り、201 の data を返す。 */
    protected JsonNode createTeamRecord(Long owner, JsonNode snapshotData, LocalDate recordedOn,
                                        Map<String, String> valuesByLabel) throws Exception {
        return expect(postCreate(owner, teamCreateBody(snapshotData, recordedOn, valuesByLabel, null),
                UUID.randomUUID().toString()), 201).path("data");
    }

    /** TEAM 定義の revision 行数（effort_definition_revisions を再読込して数える）。 */
    protected long revisionCount(String definitionUuid) {
        return count("SELECT COUNT(*) FROM effort_definition_revisions WHERE definition_id = UNHEX(REPLACE(?1, '-', ''))",
                definitionUuid);
    }

    /** 保存された revision の定義 JSON（再読込）。 */
    protected JsonNode storedRevision(String definitionUuid, int revision) throws Exception {
        String json = tx(() -> String.valueOf(em.createNativeQuery(
                        "SELECT CAST(definition_json AS CHAR) FROM effort_definition_revisions "
                                + "WHERE definition_id = UNHEX(REPLACE(:id, '-', '')) AND revision = :rev")
                .setParameter("id", definitionUuid).setParameter("rev", revision).getSingleResult()));
        return mapper.readTree(json);
    }

    /** 保存された全 revision の定義 JSON（revision 昇順・再読込）。 */
    protected List<JsonNode> storedRevisions(String definitionUuid) throws Exception {
        List<?> rows = tx(() -> em.createNativeQuery(
                        "SELECT CAST(definition_json AS CHAR) FROM effort_definition_revisions "
                                + "WHERE definition_id = UNHEX(REPLACE(:id, '-', '')) ORDER BY revision")
                .setParameter("id", definitionUuid).getResultList());
        List<JsonNode> result = new ArrayList<>();
        for (Object row : rows) {
            result.add(mapper.readTree(String.valueOf(row)));
        }
        return result;
    }

    /** プリセット revision 1 の定義 JSON（migration seed の正本）を返す。 */
    protected String presetSnapshot(UUID definitionId) {
        return tx(() -> String.valueOf(em.createNativeQuery(
                        "SELECT CAST(definition_json AS CHAR) FROM effort_definition_revisions "
                                + "WHERE definition_id = UNHEX(REPLACE(:id, '-', '')) AND revision = 1")
                .setParameter("id", definitionId.toString()).getSingleResult()));
    }

    /**
     * 集計・一覧の fixture を Service を通さず実 Repository で直接永続化する（集計部隊と個人 Service 部隊が並行で
     * green 化できるよう、作成 API の実装に依存させない）。createdAt は明示指定する。
     */
    protected UUID seedRecord(Long owner, UUID definition, LocalDate recordedOn, String timezone,
                              Map<String, String> values, Instant createdAt) {
        return seedRecord(owner, definition, recordedOn, timezone, values, createdAt, null);
    }

    protected UUID seedRecord(Long owner, UUID definition, LocalDate recordedOn, String timezone,
                              Map<String, String> values, Instant createdAt, UUID fixedId) {
        String snapshot = presetSnapshot(definition);
        return tx(() -> {
            EffortRecordEntity.EffortRecordEntityBuilder<?, ?> builder = EffortRecordEntity.builder()
                    .ownerUserId(owner).definitionId(definition).definitionRevision(1)
                    .definitionSnapshot(snapshot).recordedOn(recordedOn).recordTimezone(timezone)
                    .valuesJson(write(values)).createdAt(createdAt).updatedAt(createdAt);
            if (fixedId != null) {
                builder.id(fixedId);
            }
            return records.saveAndFlush(builder.build()).getId();
        });
    }

    // ───────────────────────────── HTTP ─────────────────────────────

    protected MockHttpServletResponse http(MockHttpServletRequestBuilder request, Long actor) throws Exception {
        if (actor != null) {
            List<String> roles = systemAdmins.contains(actor) ? List.of("MEMBER", "SYSTEM_ADMIN") : List.of("MEMBER");
            request.header("Authorization", "Bearer " + tokens.issueAccessToken(actor, roles));
        }
        return mvc.perform(request).andReturn().getResponse();
    }

    protected JsonNode json(MockHttpServletResponse response) throws Exception {
        return mapper.readTree(response.getContentAsString(StandardCharsets.UTF_8));
    }

    /** ステータスを本文付きで検証する（失敗時に原因が読めるよう本文を as に載せる）。 */
    protected JsonNode expect(MockHttpServletResponse response, int status) throws Exception {
        assertThat(response.getStatus()).as(response.getContentAsString(StandardCharsets.UTF_8)).isEqualTo(status);
        String body = response.getContentAsString(StandardCharsets.UTF_8);
        return body.isEmpty() ? mapper.nullNode() : mapper.readTree(body);
    }

    protected JsonNode expectError(MockHttpServletResponse response, int status, String code) throws Exception {
        JsonNode body = expect(response, status);
        assertThat(body.at("/error/code").asText()).as(body.toString()).isEqualTo(code);
        return body;
    }

    /** 作成リクエスト本文。値 null は JSON null として送る。 */
    protected Map<String, Object> createBody(UUID definition, LocalDate recordedOn, String timezone,
                                             Map<String, String> values, String privateNote) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("recordedOn", recordedOn.toString());
        body.put("recordTimezone", timezone);
        body.put("definitionUuid", definition.toString());
        body.put("definitionRevision", 1);
        body.put("definitionToken", null);
        body.put("values", valueList(values));
        body.put("privateNote", privateNote);
        return body;
    }

    protected List<Map<String, Object>> valueList(Map<String, String> values) {
        List<Map<String, Object>> list = new ArrayList<>();
        values.forEach((key, value) -> {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("fieldKey", key);
            entry.put("value", value);
            list.add(entry);
        });
        return list;
    }

    protected MockHttpServletResponse postCreate(Long actor, Object body, String idempotencyKey) throws Exception {
        MockHttpServletRequestBuilder request = post(ME).contentType(MediaType.APPLICATION_JSON).content(write(body));
        if (idempotencyKey != null) {
            request.header("Idempotency-Key", idempotencyKey);
        }
        return http(request, actor);
    }

    /** 作成 API で原本を作り、201 の data を返す。 */
    protected JsonNode create(Long owner, UUID definition, LocalDate recordedOn, Map<String, String> values, String note)
            throws Exception {
        return expect(postCreate(owner, createBody(definition, recordedOn, TOKYO, values, note),
                UUID.randomUUID().toString()), 201).path("data");
    }

    /** 値の JSON 表現。null を保持するため LinkedHashMap で組む。 */
    protected static Map<String, String> values(String... keyValues) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put(keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    /** owner 応答の values 配列から fieldKey の値を取り出す（null は JSON null）。 */
    protected static JsonNode valueOf(JsonNode record, String fieldKey) {
        for (JsonNode entry : record.path("values")) {
            if (fieldKey.equals(entry.path("fieldKey").asText())) {
                return entry.path("value");
            }
        }
        throw new AssertionError("values に fieldKey=" + fieldKey + " が無い: " + record);
    }

    protected String write(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    protected <T> T tx(Supplier<T> work) {
        return new TransactionTemplate(txManager).execute(status -> work.get());
    }

    protected long count(String sql, Object... params) {
        return tx(() -> {
            var query = em.createNativeQuery(sql);
            for (int i = 0; i < params.length; i++) {
                query.setParameter(i + 1, params[i]);
            }
            return ((Number) query.getSingleResult()).longValue();
        });
    }

    // ─────────────────────── プリセット seed（migration 正本） ───────────────────────

    private static final Object SEED_LOCK = new Object();

    /**
     * 標準プリセット定義行（V261 seed の effort_definitions 行を V264 適用後の列だけで書いたもの）。
     * UUID・source_key・current_revision は V261 seed と同値。revision 行は migration から読む。
     */
    private static final String PRESET_DEFINITION_ROWS_AFTER_V264 = """
            INSERT INTO effort_definitions
              (id, source_type, source_key, team_id, team_template_id, current_revision, retired_at, version, created_at, updated_at, deleted_at)
            VALUES
              (UNHEX(REPLACE('01938a00-0000-7000-8000-000000000001', '-', '')), 'PRESET', 'STUDY_V1', NULL, NULL, 1, NULL, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), NULL),
              (UNHEX(REPLACE('01938a00-0000-7000-8000-000000000002', '-', '')), 'PRESET', 'EXERCISE_V1', NULL, NULL, 1, NULL, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), NULL),
              (UNHEX(REPLACE('01938a00-0000-7000-8000-000000000003', '-', '')), 'PRESET', 'WEIGHT_V1', NULL, NULL, 1, NULL, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), NULL)
            """;

    static void seedPresetsFromMigration(DataSource dataSource) throws Exception {
        synchronized (SEED_LOCK) {
            try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
                try (var rs = statement.executeQuery(
                        "SELECT COUNT(*) FROM effort_definitions WHERE source_type = 'PRESET'")) {
                    rs.next();
                    if (rs.getLong(1) > 0) {
                        return;
                    }
                }
                boolean autoCommit = connection.getAutoCommit();
                connection.setAutoCommit(false);
                try {
                    // V261 の effort_definitions INSERT は organization_id 列を名指しするが、この列は V264 で削除済みで
                    // ddl-auto=create の現行スキーマには無い。適用済み migration は Flyway checksum を壊すため書き換えられない
                    // （backend/.claudecode.md）。そこで定義行だけは V264 適用後相当の列で試験側が入れ、
                    // 内容の正本である revision 行（定義 JSON・hash）は引き続き migration から実行する。
                    statement.execute(PRESET_DEFINITION_ROWS_AFTER_V264);
                    int skipped = 0;
                    for (String sql : presetSeedStatements()) {
                        if (sql.split("\\s+", 4)[2].equals("effort_definitions")) {
                            skipped++;
                            continue;
                        }
                        statement.execute(sql);
                    }
                    assertThat(skipped).as("migration seed 節の effort_definitions INSERT は1文だけ（試験側の定義行と置き換える）")
                            .isEqualTo(1);
                    connection.commit();
                } finally {
                    connection.setAutoCommit(autoCommit);
                }
            }
        }
    }

    /** V*__create_effort_records_tables.sql の seed 節を文単位に切り出す（版番号の付け替えに追従するため名前で探す）。 */
    static List<String> presetSeedStatements() throws Exception {
        Resource[] found = new PathMatchingResourcePatternResolver()
                .getResources("classpath*:db/migration/V*__create_effort_records_tables.sql");
        assertThat(found).as("effort 表の migration がちょうど1本ある").hasSize(1);
        String sql = new String(found[0].getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int start = sql.indexOf("-- 標準プリセット定義 seed");
        assertThat(start).as("migration に標準プリセット seed 節がある").isNotNegative();
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String line : sql.substring(start).split("\\R")) {
            if (line.stripLeading().startsWith("--")) {
                continue;
            }
            current.append(line).append('\n');
            if (line.stripTrailing().endsWith(";")) {
                String statement = current.toString().strip();
                statements.add(statement.substring(0, statement.length() - 1));
                current.setLength(0);
            }
        }
        assertThat(statements).as("seed 節の文").isNotEmpty();
        return statements;
    }
}
