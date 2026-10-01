package com.mannschaft.app.tournament.entry;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.auth.entity.UserInterestTagEntity;
import com.mannschaft.app.auth.repository.UserInterestTagRepository;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.support.test.MembershipTestHelper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * <b>大会エントリー系・興味タグの主キー型ずれ（CMP-260929-0654）を、Flyway 実スキーマの上で API 経由で検証する結合テスト。</b>
 *
 * <h2>なぜ専用の IT が要るか</h2>
 * <p>{@code test} プロファイルの既定は {@code ddl-auto=create} + Flyway 無効で、テーブルは Entity から生成される。
 * そのため「DDL が CHAR(36)・Entity は BINARY(16)」という型ずれは自己整合して必ず緑になり、
 * 実 DB では保存のたびに {@code Incorrect string value} で落ちる欠陥が原理的に見えなかった。
 * 本クラスは {@code spring.flyway.enabled=true} / {@code ddl-auto=none} に上書きし、
 * 実 Flyway スキーマ（Testcontainers MySQL 8.0）に対して MockMvc で API を叩く。</p>
 *
 * <p>トランザクションは張らない（{@code @Transactional} を付けない）。AC-10 の「途中失敗でロールバックされること」は
 * テストのトランザクションが Service のそれを飲み込むと測れないため、シードは自動コミットで確定させ、
 * 各テストが固有のデータ（UUID 接尾辞）を作って互いに干渉しないようにしている。</p>
 *
 * <p>対応 AC: AC-1（PUT/GET）・AC-2（テンプレート POST/GET/PUT）・AC-3（DELETE と CASCADE）・
 * AC-4（興味タグ）・AC-8（境界値）・AC-9（空）・AC-10a（途中失敗のロールバック）。</p>
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=none"
})
@ActiveProfiles("test")
@Testcontainers
@AutoConfigureMockMvc(addFilters = false)
@EnabledIf("com.mannschaft.app.tournament.entry.TournamentEntryPkTypeFlywayIT#isDockerAvailable")
@DisplayName("大会エントリー系 主キー型 Flyway 実スキーマ API テスト（CMP-260929-0654）")
class TournamentEntryPkTypeFlywayIT {

    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("mannschaft_entry_pk_type")
            .withUsername("test")
            .withPassword("test")
            .withTmpFs(Map.of("/var/lib/mysql", "rw"))
            .withCommand("--log_bin_trust_function_creators=1");

    static {
        if (isDockerAvailable()) {
            MYSQL.start();
            awaitRealConnectivity();
        }
    }

    /** コンテナ起動直後はポートが開いていてもハンドシェイクが終わっていないことがあるため、実接続を待つ。 */
    private static void awaitRealConnectivity() {
        long deadline = System.currentTimeMillis() + 180_000L;
        RuntimeException last = null;
        while (System.currentTimeMillis() < deadline) {
            try (java.sql.Connection c = java.sql.DriverManager.getConnection(
                    MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
                 java.sql.Statement st = c.createStatement()) {
                st.execute("SELECT 1");
                return;
            } catch (Exception e) {
                last = new IllegalStateException("MySQL への実接続がまだ成立しない", e);
                try {
                    Thread.sleep(2_000L);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(ie);
                }
            }
        }
        throw last != null ? last : new IllegalStateException("MySQL への実接続がタイムアウトした");
    }

    @MockitoBean
    org.springframework.data.redis.core.StringRedisTemplate redisTemplate;

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    public static boolean isDockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Exception e) {
            return false;
        }
    }

    private static final String ENTRY_MEMBERS =
            "/api/v1/organizations/{orgId}/tournaments/{tId}/divisions/{divId}/participants/{pId}/entry-members";
    private static final String TEMPLATES =
            "/api/v1/organizations/{orgId}/teams/{teamId}/entry-templates";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private TransactionTemplate tx;
    @Autowired
    private UserInterestTagRepository interestTagRepository;
    @PersistenceContext
    private EntityManager em;

    private String suffix;
    private Long orgId;
    private Long teamId;
    private Long orgAdminId;
    private Long teamAdminId;
    private Long outsiderId;
    private Long player1;
    private Long player2;
    private Long player3;
    private Long tournamentId;
    private Long divisionId;
    private Long participantId;

    @BeforeEach
    void setUp() {
        suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        jdbc.update("INSERT INTO roles (name, display_name, priority, is_system, created_at, updated_at) "
                + "SELECT 'ADMIN', '管理者', 2, 0, NOW(), NOW() FROM DUAL "
                + "WHERE NOT EXISTS (SELECT 1 FROM roles WHERE name = 'ADMIN')");
        jdbc.update("INSERT INTO roles (name, display_name, priority, is_system, created_at, updated_at) "
                + "SELECT 'MEMBER', 'メンバー', 4, 0, NOW(), NOW() FROM DUAL "
                + "WHERE NOT EXISTS (SELECT 1 FROM roles WHERE name = 'MEMBER')");

        orgId = insertOrganization("PK型ORG" + suffix);
        teamId = insertTeam("PK型TEAM" + suffix);
        jdbc.update("INSERT INTO team_org_memberships (team_id, organization_id, status, invited_at, created_at) "
                + "VALUES (?, ?, 'ACTIVE', NOW(), NOW())", teamId, orgId);

        orgAdminId = insertUser("pk-orgadmin-" + suffix + "@example.com");
        teamAdminId = insertUser("pk-teamadmin-" + suffix + "@example.com");
        outsiderId = insertUser("pk-outsider-" + suffix + "@example.com");
        player1 = insertUser("pk-p1-" + suffix + "@example.com");
        player2 = insertUser("pk-p2-" + suffix + "@example.com");
        player3 = insertUser("pk-p3-" + suffix + "@example.com");

        tx.executeWithoutResult(status -> {
            MembershipTestHelper.insertUserRole(em, orgAdminId, "ADMIN", null, orgId);
            MembershipTestHelper.insertMembership(em, orgAdminId, ScopeType.ORGANIZATION, orgId, RoleKind.MEMBER);
            MembershipTestHelper.insertUserRole(em, teamAdminId, "ADMIN", teamId, null);
            MembershipTestHelper.insertMembership(em, teamAdminId, ScopeType.TEAM, teamId, RoleKind.MEMBER);
        });

        tournamentId = insertTournament(orgId, "PK型大会" + suffix, orgAdminId);
        divisionId = insertDivision(tournamentId, "PK型部" + suffix);
        participantId = insertParticipant(divisionId, teamId);
    }

    @AfterEach
    void clearAuth() {
        SecurityContextHolder.clearContext();
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-1: PUT entry-members → 200、GET で同じ内容
    // ═════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("AC-1: 実スキーマで PUT entry-members が200を返し、GET で同じ内容が返る")
    void AC1_PUTが実スキーマで200を返しGETで同じ内容が返る() throws Exception {
        setAuth(teamAdminId);
        Map<String, Object> body = membersBody(
                member(player1, 10, "FW", "主将", 0),
                member(player2, 7, "MF", null, 1));

        MvcResult put = mockMvc.perform(put(ENTRY_MEMBERS, orgId, tournamentId, divisionId, participantId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
        assertThat(put.getResponse().getStatus()).as("PUT の本文: %s", put.getResponse().getContentAsString()).isEqualTo(200);

        MvcResult get = mockMvc.perform(get(ENTRY_MEMBERS, orgId, tournamentId, divisionId, participantId))
                .andReturn();
        assertThat(get.getResponse().getStatus()).isEqualTo(200);
        JsonNode members = json(get).at("/data/entryMembers");
        assertThat(members).hasSize(2);
        assertThat(members.get(0).get("userId").asLong()).isEqualTo(player1);
        assertThat(members.get(0).get("jerseyNumber").asInt()).isEqualTo(10);
        assertThat(members.get(0).get("position").asText()).isEqualTo("FW");
        assertThat(members.get(0).get("notes").asText()).isEqualTo("主将");
        assertThat(members.get(1).get("userId").asLong()).isEqualTo(player2);
        assertThat(members.get(1).get("notes").isNull()).isTrue();
        // 主キーは BINARY(16) として保存されている（UUID 文字列の HEX と一致する）
        String id = members.get(0).get("id").asText();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM tournament_entry_members WHERE HEX(id) = UPPER(REPLACE(?, '-', ''))",
                Long.class, id)).isEqualTo(1L);
    }

    @Test
    @DisplayName("AC-1: 同じユーザーを含む内容で PUT を繰り返しても200（全削除→再作成で一意制約に当たらない）")
    void AC1_同じユーザーを含む全置換を繰り返しても200() throws Exception {
        setAuth(teamAdminId);
        Map<String, Object> body = membersBody(member(player1, 10, "FW", null, 0));
        for (int i = 0; i < 2; i++) {
            MvcResult put = mockMvc.perform(put(ENTRY_MEMBERS, orgId, tournamentId, divisionId, participantId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body)))
                    .andReturn();
            assertThat(put.getResponse().getStatus())
                    .as("%d 回目の PUT の本文: %s", i + 1, put.getResponse().getContentAsString()).isEqualTo(200);
        }
        assertThat(countEntryMembers()).isEqualTo(1L);
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-2: テンプレート POST 201（created_by）・GET・PUT
    // ═════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("AC-2: テンプレート POST が201で created_by に作成者が入り、GET と PUT も成功する")
    void AC2_テンプレートのPOSTとGETとPUTが成功する() throws Exception {
        setAuth(teamAdminId);
        MvcResult post = mockMvc.perform(post(TEMPLATES, orgId, teamId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(templateBody("テンプレ" + suffix,
                                templateMember(player1, 10, "FW", 0), templateMember(player2, 7, "MF", 1)))))
                .andReturn();
        assertThat(post.getResponse().getStatus()).as("POST の本文: %s", post.getResponse().getContentAsString()).isEqualTo(201);
        String templateId = json(post).at("/data/id").asText();
        assertThat(templateId).isNotBlank();
        assertThat(jdbc.queryForObject(
                "SELECT created_by FROM tournament_entry_templates WHERE HEX(id) = UPPER(REPLACE(?, '-', ''))",
                Long.class, templateId)).as("created_by に作成者").isEqualTo(teamAdminId);

        MvcResult list = mockMvc.perform(get(TEMPLATES, orgId, teamId)).andReturn();
        assertThat(list.getResponse().getStatus()).isEqualTo(200);
        assertThat(json(list).at("/data/0/memberCount").asInt()).isEqualTo(2);

        MvcResult detail = mockMvc.perform(get(TEMPLATES + "/{tplId}", orgId, teamId, templateId)).andReturn();
        assertThat(detail.getResponse().getStatus()).isEqualTo(200);
        assertThat(json(detail).at("/data/members")).hasSize(2);
        assertThat(json(detail).at("/data/members/0/position").asText()).isEqualTo("FW");

        MvcResult put = mockMvc.perform(put(TEMPLATES + "/{tplId}", orgId, teamId, templateId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(templateBody("テンプレ更新" + suffix,
                                templateMember(player1, 9, "DF", 0), templateMember(player3, 3, "GK", 1)))))
                .andReturn();
        assertThat(put.getResponse().getStatus()).as("PUT の本文: %s", put.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(json(put).at("/data/members")).hasSize(2);
        assertThat(json(put).at("/data/members/0/jerseyNumber").asInt()).isEqualTo(9);
        assertThat(json(put).at("/data/members/1/userId").asLong()).isEqualTo(player3);
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-3: DELETE は論理削除。親を物理削除した場合だけ CASCADE
    // ═════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("AC-3: API の DELETE は204で親を論理削除し子は残る。親を SQL で物理削除した場合だけ member と staff が CASCADE で消える")
    void AC3_DELETEは論理削除で物理削除の時だけCASCADEする() throws Exception {
        setAuth(teamAdminId);
        MvcResult post = mockMvc.perform(post(TEMPLATES, orgId, teamId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(templateBody("削除" + suffix,
                                templateMember(player1, 10, "FW", 0), templateMember(player2, 7, "MF", 1)))))
                .andReturn();
        assertThat(post.getResponse().getStatus()).isEqualTo(201);
        String templateId = json(post).at("/data/id").asText();
        // ベンチ役員（staff）は API 経路が別のため SQL で1行入れる（staff.id は元から BINARY(16)）
        jdbc.update("INSERT INTO tournament_entry_template_staff (id, template_id, role, name, sort_order) "
                + "VALUES (UUID_TO_BIN(UUID()), UNHEX(REPLACE(?, '-', '')), '監督', '山田', 0)", templateId);

        MvcResult del = mockMvc.perform(delete(TEMPLATES + "/{tplId}", orgId, teamId, templateId)).andReturn();
        assertThat(del.getResponse().getStatus()).isEqualTo(204);

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM tournament_entry_templates WHERE HEX(id) = UPPER(REPLACE(?, '-', '')) "
                        + "AND deleted_at IS NOT NULL", Long.class, templateId)).as("親は論理削除").isEqualTo(1L);
        assertThat(templateChildCount("tournament_entry_template_members", templateId)).as("member は残る").isEqualTo(2L);
        assertThat(templateChildCount("tournament_entry_template_staff", templateId)).as("staff は残る").isEqualTo(1L);

        jdbc.update("DELETE FROM tournament_entry_templates WHERE HEX(id) = UPPER(REPLACE(?, '-', ''))", templateId);
        assertThat(templateChildCount("tournament_entry_template_members", templateId)).as("物理削除で member が CASCADE").isZero();
        assertThat(templateChildCount("tournament_entry_template_staff", templateId)).as("物理削除で staff が CASCADE").isZero();
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-4: user_interest_tags の保存と取得
    // ═════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("AC-4: user_interest_tags を保存して取得できる（主キーは BINARY(16)）")
    void AC4_興味タグを保存して取得できる() {
        long userId = 900_000_000L + Math.abs(suffix.hashCode() % 1_000_000);
        UserInterestTagEntity saved = interestTagRepository.save(
                UserInterestTagEntity.create(userId, "soccer", "h".repeat(64)));
        assertThat(saved.getId()).isNotNull();

        List<UserInterestTagEntity> found = interestTagRepository.findByUserId(userId);
        assertThat(found).hasSize(1);
        assertThat(found.get(0).getId()).isEqualTo(saved.getId());
        assertThat(found.get(0).getTag()).isEqualTo("soccer");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM user_interest_tags WHERE HEX(id) = UPPER(REPLACE(?, '-', ''))",
                Long.class, saved.getId().toString())).isEqualTo(1L);
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-8: 境界値（position 30 字・notes 200 字）
    // ═════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("AC-8: メンバー API の position は30字で成功し、31字は400で fieldErrors に position")
    void AC8_メンバーAPIのpositionの境界() throws Exception {
        setAuth(teamAdminId);
        assertThat(putMembers(membersBody(member(player1, 1, "あ".repeat(30), null, 0))).getResponse().getStatus())
                .as("30 字").isEqualTo(200);
        MvcResult over = putMembers(membersBody(member(player1, 1, "あ".repeat(31), null, 0)));
        assertThat(over.getResponse().getStatus()).as("31 字").isEqualTo(400);
        assertThat(over.getResponse().getContentAsString()).contains("position");
    }

    @Test
    @DisplayName("AC-8: メンバー API の notes は200字で成功し、201字は400で fieldErrors に notes")
    void AC8_メンバーAPIのnotesの境界() throws Exception {
        setAuth(teamAdminId);
        assertThat(putMembers(membersBody(member(player1, 1, "FW", "あ".repeat(200), 0))).getResponse().getStatus())
                .as("200 字").isEqualTo(200);
        MvcResult over = putMembers(membersBody(member(player1, 1, "FW", "あ".repeat(201), 0)));
        assertThat(over.getResponse().getStatus()).as("201 字").isEqualTo(400);
        assertThat(over.getResponse().getContentAsString()).contains("notes");
    }

    @Test
    @DisplayName("AC-8: テンプレート API の position は30字で201、31字は400で fieldErrors に position（テンプレートに notes は無く description が200字上限）")
    void AC8_テンプレートAPIのpositionの境界() throws Exception {
        setAuth(teamAdminId);
        MvcResult ok = postTemplate(templateBody("境界" + suffix, templateMember(player1, 1, "あ".repeat(30), 0)));
        assertThat(ok.getResponse().getStatus()).as("30 字: %s", ok.getResponse().getContentAsString()).isEqualTo(201);

        MvcResult over = postTemplate(templateBody("境界2" + suffix, templateMember(player1, 1, "あ".repeat(31), 0)));
        assertThat(over.getResponse().getStatus()).as("31 字").isEqualTo(400);
        assertThat(over.getResponse().getContentAsString()).contains("position");

        // 更新 API も同じ上限
        String templateId = json(ok).at("/data/id").asText();
        MvcResult putOver = mockMvc.perform(put(TEMPLATES + "/{tplId}", orgId, teamId, templateId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(templateBody("境界3" + suffix,
                                templateMember(player1, 1, "あ".repeat(31), 0)))))
                .andReturn();
        assertThat(putOver.getResponse().getStatus()).as("PUT 31 字").isEqualTo(400);
        assertThat(putOver.getResponse().getContentAsString()).contains("position");

        // description は 200 字まで（DDL の VARCHAR(200) と一致）
        Map<String, Object> descOk = templateBody("境界4" + suffix, templateMember(player1, 1, "FW", 0));
        descOk.put("description", "あ".repeat(200));
        assertThat(postTemplate(descOk).getResponse().getStatus()).as("description 200 字").isEqualTo(201);
        Map<String, Object> descOver = templateBody("境界5" + suffix, templateMember(player1, 1, "FW", 0));
        descOver.put("description", "あ".repeat(201));
        assertThat(postTemplate(descOver).getResponse().getStatus()).as("description 201 字").isEqualTo(400);
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-9: 空
    // ═════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("AC-9: min_entry_count が null のとき0件の PUT は200で全削除される")
    void AC9_minがnullなら0件のPUTは全削除() throws Exception {
        setAuth(teamAdminId);
        seedEntryMembers();
        jdbc.update("UPDATE tournament_divisions SET min_entry_count = NULL WHERE id = ?", divisionId);
        assertThat(putMembers(membersBody()).getResponse().getStatus()).isEqualTo(200);
        assertThat(countEntryMembers()).isZero();
    }

    @Test
    @DisplayName("AC-9: min_entry_count が 0 のとき0件の PUT は200で全削除される")
    void AC9_minが0なら0件のPUTは全削除() throws Exception {
        setAuth(teamAdminId);
        seedEntryMembers();
        jdbc.update("UPDATE tournament_divisions SET min_entry_count = 0 WHERE id = ?", divisionId);
        assertThat(putMembers(membersBody()).getResponse().getStatus()).isEqualTo(200);
        assertThat(countEntryMembers()).isZero();
    }

    @Test
    @DisplayName("AC-9: min_entry_count が1以上のとき0件の PUT は4xxで旧データが残る")
    void AC9_minが1以上なら0件のPUTは4xxで旧データが残る() throws Exception {
        setAuth(teamAdminId);
        seedEntryMembers();
        jdbc.update("UPDATE tournament_divisions SET min_entry_count = 1 WHERE id = ?", divisionId);
        int status = putMembers(membersBody()).getResponse().getStatus();
        assertThat(status).as("最少人数違反は 4xx").isBetween(400, 499);
        assertThat(countEntryMembers()).as("旧データが残る").isEqualTo(2L);
    }

    @Test
    @DisplayName("AC-9: position・notes が null でも保存でき、メンバー0人のテンプレートも POST が201")
    void AC9_nullの項目と空のテンプレートを保存できる() throws Exception {
        setAuth(teamAdminId);
        assertThat(putMembers(membersBody(member(player1, null, null, null, 0))).getResponse().getStatus()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tournament_entry_members WHERE participant_id = ? "
                + "AND position IS NULL AND notes IS NULL", Long.class, participantId)).isEqualTo(1L);

        MvcResult empty = postTemplate(templateBody("空" + suffix));
        assertThat(empty.getResponse().getStatus()).as("本文: %s", empty.getResponse().getContentAsString()).isEqualTo(201);
        assertThat(json(empty).at("/data/members")).isEmpty();
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-10a: 置換 PUT の途中失敗でロールバックされる
    // ═════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("AC-10: 置換 PUT が保存段階の一意制約違反で落ちたとき、旧行の件数と内容が完全に元へ戻る（エントリー表）")
    void AC10_エントリー表の置換PUTが途中失敗したら旧行が元へ戻る() throws Exception {
        setAuth(teamAdminId);
        seedEntryMembers();
        List<Map<String, Object>> before = snapshotEntryMembers();
        assertThat(before).hasSize(2);

        // 同じ userId を2回含める。@Valid は通り抜け、Service 内の保存（uq_tem_participant_user）で落ちる
        int status = statusOrThrown(() -> putMembers(membersBody(
                member(player1, 1, "GK", "新", 0), member(player2, 2, "DF", "新", 1), member(player2, 3, "MF", "新", 2))));
        assertThat(status).as("一意制約違反は成功扱いにならない").isNotEqualTo(200);

        assertThat(snapshotEntryMembers()).as("旧行の件数・id・内容が完全に元へ戻る").isEqualTo(before);
    }

    @Test
    @DisplayName("AC-10: テンプレートの置換 PUT が一意制約違反で落ちたとき、旧メンバーが元へ戻り親の更新も巻き戻る")
    void AC10_テンプレートの置換PUTが途中失敗したら旧行が元へ戻る() throws Exception {
        setAuth(teamAdminId);
        MvcResult post = postTemplate(templateBody("元" + suffix,
                templateMember(player1, 10, "FW", 0), templateMember(player2, 7, "MF", 1)));
        assertThat(post.getResponse().getStatus()).isEqualTo(201);
        String templateId = json(post).at("/data/id").asText();
        List<Map<String, Object>> before = snapshotTemplateMembers(templateId);
        assertThat(before).hasSize(2);

        int status = statusOrThrown(() -> mockMvc.perform(put(TEMPLATES + "/{tplId}", orgId, teamId, templateId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(templateBody("改" + suffix,
                                templateMember(player1, 1, "GK", 0), templateMember(player1, 2, "DF", 1)))))
                .andReturn());
        assertThat(status).isNotEqualTo(200);

        assertThat(snapshotTemplateMembers(templateId)).as("旧メンバーが元へ戻る").isEqualTo(before);
        assertThat(jdbc.queryForObject(
                "SELECT name FROM tournament_entry_templates WHERE HEX(id) = UPPER(REPLACE(?, '-', ''))",
                String.class, templateId)).as("親の名前更新も巻き戻る").isEqualTo("元" + suffix);
    }

    // ═════════════════════════════════════════════════════════════════════
    // ヘルパー
    // ═════════════════════════════════════════════════════════════════════

    private interface Call {
        MvcResult run() throws Exception;
    }

    /** 例外が MockMvc の外へ漏れた場合も「失敗」として 500 に写す。 */
    private static int statusOrThrown(Call call) {
        try {
            return call.run().getResponse().getStatus();
        } catch (Exception e) {
            return 500;
        }
    }

    private MvcResult putMembers(Map<String, Object> body) throws Exception {
        return mockMvc.perform(put(ENTRY_MEMBERS, orgId, tournamentId, divisionId, participantId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body))).andReturn();
    }

    private MvcResult postTemplate(Map<String, Object> body) throws Exception {
        return mockMvc.perform(post(TEMPLATES, orgId, teamId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body))).andReturn();
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private void seedEntryMembers() throws Exception {
        assertThat(putMembers(membersBody(
                member(player1, 10, "FW", "旧1", 0), member(player2, 7, "MF", "旧2", 1))).getResponse().getStatus())
                .isEqualTo(200);
    }

    private long countEntryMembers() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM tournament_entry_members WHERE participant_id = ?",
                Long.class, participantId);
    }

    private long templateChildCount(String table, String templateId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table
                + " WHERE HEX(template_id) = UPPER(REPLACE(?, '-', ''))", Long.class, templateId);
    }

    private List<Map<String, Object>> snapshotEntryMembers() {
        return new ArrayList<>(jdbc.queryForList(
                "SELECT HEX(id) AS id, user_id, jersey_number, position, notes, sort_order "
                        + "FROM tournament_entry_members WHERE participant_id = ? ORDER BY sort_order, user_id",
                participantId));
    }

    private List<Map<String, Object>> snapshotTemplateMembers(String templateId) {
        return new ArrayList<>(jdbc.queryForList(
                "SELECT HEX(id) AS id, user_id, jersey_number, position, sort_order "
                        + "FROM tournament_entry_template_members WHERE HEX(template_id) = UPPER(REPLACE(?, '-', '')) "
                        + "ORDER BY sort_order, user_id", templateId));
    }

    private static Map<String, Object> member(Long userId, Integer jersey, String position, String notes, int sortOrder) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("userId", userId);
        m.put("jerseyNumber", jersey);
        m.put("position", position);
        m.put("notes", notes);
        m.put("sortOrder", sortOrder);
        return m;
    }

    @SafeVarargs
    private static Map<String, Object> membersBody(Map<String, Object>... members) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("members", List.of(members));
        return body;
    }

    private static Map<String, Object> templateMember(Long userId, Integer jersey, String position, int sortOrder) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("userId", userId);
        m.put("jerseyNumber", jersey);
        m.put("position", position);
        m.put("sortOrder", sortOrder);
        return m;
    }

    @SafeVarargs
    private static Map<String, Object> templateBody(String name, Map<String, Object>... members) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("sortOrder", 0);
        body.put("members", List.of(members));
        return body;
    }

    private void setAuth(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
    }

    private Long insertUser(String email) {
        jdbc.update("INSERT INTO users ("
                + "email, last_name, first_name, display_name, status, "
                + "is_searchable, handle_searchable, contact_approval_required, "
                + "online_visibility, dm_receive_from, encryption_key_version, "
                + "locale, timezone, reporting_restricted, follow_list_visibility, "
                + "care_notification_enabled, offline_only, created_at, updated_at) "
                + "VALUES (?, 'PK型', 'テスト', 'PK型テスト', 'ACTIVE', "
                + "1, 1, 1, 'NOBODY', 'ANYONE', 1, 'ja', 'Asia/Tokyo', 0, 'PUBLIC', 1, 0, NOW(), NOW())", email);
        return jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
    }

    private Long insertOrganization(String name) {
        jdbc.update("INSERT INTO organizations (name, org_type, visibility, hierarchy_visibility, "
                + "supporter_enabled, version, slug, created_at, updated_at) "
                + "VALUES (?, 'OTHER', 'PUBLIC', 'NONE', 1, 0, CONCAT('pk-', LEFT(REPLACE(UUID(),'-',''),8)), NOW(), NOW())", name);
        return jdbc.queryForObject("SELECT id FROM organizations WHERE name = ?", Long.class, name);
    }

    private Long insertTeam(String name) {
        jdbc.update("INSERT INTO teams (name, visibility, supporter_enabled, version, member_count, slug, "
                + "created_at, updated_at) "
                + "VALUES (?, 'PUBLIC', 1, 0, 0, CONCAT('pk-', LEFT(REPLACE(UUID(),'-',''),8)), NOW(), NOW())", name);
        return jdbc.queryForObject("SELECT id FROM teams WHERE name = ?", Long.class, name);
    }

    private Long insertTournament(Long organizationId, String name, Long createdBy) {
        jdbc.update("INSERT INTO tournaments (organization_id, name, format, sport, "
                + "win_points, draw_points, loss_points, has_draw, has_sets, "
                + "has_extra_time, has_penalties, score_unit_label, league_round_type, "
                + "knockout_legs, visibility, status, version, created_by, created_at, updated_at) "
                + "VALUES (?, ?, 'LEAGUE', 'SOCCER', 3, 1, 0, 1, 0, 0, 0, '点', 'SINGLE', "
                + "1, 'PUBLIC', 'OPEN', 0, ?, NOW(), NOW())", organizationId, name, createdBy);
        return jdbc.queryForObject("SELECT id FROM tournaments WHERE name = ?", Long.class, name);
    }

    private Long insertDivision(Long tId, String name) {
        jdbc.update("INSERT INTO tournament_divisions (tournament_id, name, level, promotion_slots, "
                + "relegation_slots, playoff_promotion_slots, sort_order, created_at, updated_at) "
                + "VALUES (?, ?, 1, 0, 0, 0, 0, NOW(), NOW())", tId, name);
        return jdbc.queryForObject("SELECT id FROM tournament_divisions WHERE name = ?", Long.class, name);
    }

    private Long insertParticipant(Long divId, Long tmId) {
        jdbc.update("INSERT INTO tournament_participants (division_id, team_id, status, joined_at) "
                + "VALUES (?, ?, 'REGISTERED', NOW())", divId, tmId);
        return jdbc.queryForObject("SELECT MAX(id) FROM tournament_participants WHERE division_id = ?", Long.class, divId);
    }
}
