package com.mannschaft.app.notification.confirmable.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CMP-260920-1040 F04.9「宛先指定」戦役 — 試練A補完（AC-16 宛先グループCRUD・送信APIのSEND_NOTIFICATION認可）。
 *
 * <p>軍議第8版確定稿 §4「認可」AC-16「SEND_NOTIFICATION の権限判定は従来どおり効く（ADMIN、
 * または権限を持つ DEPUTY_ADMIN だけが送れる）。宛先グループの作成・編集・削除も同じ権限で判定する」
 * を対象とする。金型は {@code ConfirmableNotificationScopeContractIT}（同ドメインの
 * {@code @AutoConfigureMockMvc(addFilters=false)} + 実MySQL + 手動SecurityContext）と
 * {@code SendNotificationPermissionCatalogIT}（migration本文をそのまま実行してカタログ行を作る手法）。</p>
 *
 * <p><b>role_permissions は role 単位のグローバル表</b>（組織・チーム列を持たない）であり、
 * 「特定組織だけ DEPUTY_ADMIN から送信権限を外す」ことはできない（migration V216
 * コメント参照）。したがって「権限ありDEPUTY」「権限なしDEPUTY」を<b>同一テスト内で
 * 同時に</b>作ることはできず、本クラスでは test メソッドごとにカタログ状態を作り直す
 * （{@code @Transactional} によりテストごとにロールバックされるため干渉しない）。</p>
 *
 * <p>{@link OrgConfirmableRecipientGroupController} は本試練で新設した骨格で、本体は
 * {@code ConfirmableRecipientGroupService} のスタブ（{@code UnsupportedOperationException}）を
 * 呼ぶ。認可チェックはコントローラ内でサービス呼び出しより<b>先に</b>実行されるため、権限を
 * 持たない操作者はサービスのスタブに到達する前に403で拒否される。権限を持つ操作者の正常系は
 * サービス未実装により500になるが、これは「403にならない」ことのみを検証し、出陣後にサービスが
 * 実装されれば201になる（本クラスの403判定テストは出陣後もそのまま green の想定）。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("宛先グループCRUD・送信API SEND_NOTIFICATION認可 試練（AC-16）")
class ConfirmableRecipientGroupAndSendAuthzIT extends AbstractMySqlIntegrationTest {

    private static final String PERMISSION = "SEND_NOTIFICATION";
    private static final String MIGRATION_RESOURCE =
            "db/migration/V216.20260918083734__add_send_notification_permission.sql";
    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @PersistenceContext
    private EntityManager em;

    private Long orgId;

    @BeforeEach
    void setUp() {
        seedRoles();
        orgId = insertOrganization();
    }

    // =====================================================================
    // 送信API
    // =====================================================================
    @Test
    @DisplayName("AC-16: 送信APIは、SEND_NOTIFICATIONの既定付与を外されたDEPUTY_ADMINに対し403")
    void ac16_send_権限のないDEPUTYは403() throws Exception {
        seedSendNotificationCatalogWithoutDeputyDefault();
        Long deputy = insertUser();
        grantRole(deputy, "DEPUTY_ADMIN", orgId);
        em.flush();
        em.clear();

        setAuth(deputy);
        mockMvc.perform(post("/api/v1/organizations/{id}/confirmable-notifications", orgId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(sendBody())))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("AC-16: 送信APIは、既定付与のままのDEPUTY_ADMINに対し403にならない")
    void ac16_send_権限のあるDEPUTYは403にならない() throws Exception {
        seedSendNotificationFromMigration();
        Long deputy = insertUser();
        grantRole(deputy, "DEPUTY_ADMIN", orgId);
        em.flush();
        em.clear();

        setAuth(deputy);
        mockMvc.perform(post("/api/v1/organizations/{id}/confirmable-notifications", orgId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(sendBody())))
                .andExpect(result -> assertThat(result.getResponse().getStatus())
                        .as("AC-16: 既定付与のあるDEPUTYは認可では拒否されない（403にならない）")
                        .isNotEqualTo(403));
    }

    @Test
    @DisplayName("AC-16: 送信APIは、MEMBERに対し403")
    void ac16_send_MEMBERは403() throws Exception {
        seedSendNotificationFromMigration();
        Long member = insertUser();
        grantRole(member, "MEMBER", orgId);
        em.flush();
        em.clear();

        setAuth(member);
        mockMvc.perform(post("/api/v1/organizations/{id}/confirmable-notifications", orgId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(sendBody())))
                .andExpect(status().isForbidden());
    }

    // =====================================================================
    // 宛先グループCRUD（AC-16「宛先グループの作成・編集・削除も同じ権限で判定する」）
    // =====================================================================
    @Test
    @DisplayName("AC-16: 宛先グループ作成は、SEND_NOTIFICATIONの既定付与を外されたDEPUTY_ADMINに対し403")
    void ac16_group_権限のないDEPUTYの作成は403() throws Exception {
        seedSendNotificationCatalogWithoutDeputyDefault();
        Long deputy = insertUser();
        grantRole(deputy, "DEPUTY_ADMIN", orgId);
        em.flush();
        em.clear();

        setAuth(deputy);
        mockMvc.perform(post("/api/v1/organizations/{id}/confirmable-recipient-groups", orgId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(groupBody())))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("AC-16: 宛先グループ作成は、MEMBERに対し403")
    void ac16_group_MEMBERの作成は403() throws Exception {
        seedSendNotificationFromMigration();
        Long member = insertUser();
        grantRole(member, "MEMBER", orgId);
        em.flush();
        em.clear();

        setAuth(member);
        mockMvc.perform(post("/api/v1/organizations/{id}/confirmable-recipient-groups", orgId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(groupBody())))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("AC-16: 宛先グループ作成は、ADMINに対し403にならない（サービス未実装の500は許容）")
    void ac16_group_ADMINの作成は403にならない() throws Exception {
        seedSendNotificationFromMigration();
        Long admin = insertUser();
        grantRole(admin, "ADMIN", orgId);
        em.flush();
        em.clear();

        setAuth(admin);
        mockMvc.perform(post("/api/v1/organizations/{id}/confirmable-recipient-groups", orgId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(groupBody())))
                .andExpect(result -> assertThat(result.getResponse().getStatus())
                        .as("AC-16: ADMINは認可では拒否されない（403にならない。サービス未実装の500は許容）")
                        .isNotEqualTo(403));
    }

    @Test
    @DisplayName("AC-16: 宛先グループ一覧取得は checkMembership のまま（非メンバーは403、メンバーは403にならない）")
    void ac16_group_一覧は非メンバーのみ403() throws Exception {
        Long outsider = insertUser();
        setAuth(outsider);
        mockMvc.perform(get("/api/v1/organizations/{id}/confirmable-recipient-groups", orgId))
                .andExpect(status().isForbidden());

        Long member = insertUser();
        grantRole(member, "MEMBER", orgId);
        em.flush();
        em.clear();
        setAuth(member);
        mockMvc.perform(get("/api/v1/organizations/{id}/confirmable-recipient-groups", orgId))
                .andExpect(result -> assertThat(result.getResponse().getStatus())
                        .as("AC-16: 一覧はメンバーであれば403にならない（SEND_NOTIFICATIONは不要）")
                        .isNotEqualTo(403));
    }

    // =====================================================================
    // ヘルパ
    // =====================================================================

    private Map<String, Object> sendBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", "AC-16認可試練");
        return body;
    }

    private Map<String, Object> groupBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "AC-16認可試練グループ-" + SEQ.incrementAndGet());
        Map<String, Object> target = new LinkedHashMap<>();
        target.put("type", "ORGANIZATION");
        target.put("id", orgId);
        body.put("targets", List.of(target));
        return body;
    }

    private void setAuth(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
    }

    private void seedRoles() {
        insertRole("SYSTEM_ADMIN", 1);
        insertRole("ADMIN", 2);
        insertRole("DEPUTY_ADMIN", 3);
        insertRole("MEMBER", 4);
        insertRole("SUPPORTER", 5);
        insertRole("GUEST", 6);
        em.flush();
    }

    private void insertRole(String name, int priority) {
        em.createNativeQuery(
                        "INSERT IGNORE INTO roles (name, display_name, priority, is_system, created_at, updated_at) "
                                + "VALUES (:name, :name, :priority, 1, NOW(), NOW())")
                .setParameter("name", name)
                .setParameter("priority", priority)
                .executeUpdate();
    }

    /** 本戦役より前の CMP-260909-1141 migration 本文をそのまま実行してカタログ行・既定付与行を作る。 */
    private void seedSendNotificationFromMigration() {
        for (String sql : readMigrationStatements()) {
            em.createNativeQuery(sql).executeUpdate();
        }
        em.flush();
    }

    /** 「運用でDEPUTY_ADMINから送信権限を外した」状態（migrationのADMIN行のみ残す）を作る。 */
    private void seedSendNotificationCatalogWithoutDeputyDefault() {
        seedSendNotificationFromMigration();
        em.createNativeQuery(
                        "DELETE rp FROM role_permissions rp "
                                + "JOIN roles r ON r.id = rp.role_id "
                                + "JOIN permissions p ON p.id = rp.permission_id "
                                + "WHERE r.name = 'DEPUTY_ADMIN' AND p.name = :perm")
                .setParameter("perm", PERMISSION)
                .executeUpdate();
        em.flush();
    }

    private List<String> readMigrationStatements() {
        String raw;
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(MIGRATION_RESOURCE)) {
            assertThat(in).as("migration ファイルが classpath 上に存在すること: " + MIGRATION_RESOURCE).isNotNull();
            raw = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("migration ファイルの読み出しに失敗した: " + MIGRATION_RESOURCE, e);
        }
        StringBuilder stripped = new StringBuilder();
        for (String line : raw.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("--")) {
                continue;
            }
            stripped.append(line).append('\n');
        }
        List<String> statements = new ArrayList<>();
        for (String part : stripped.toString().split(";")) {
            String sql = part.trim();
            if (!sql.isEmpty()) {
                statements.add(sql);
            }
        }
        return statements;
    }

    private Long insertUser() {
        int n = SEQ.incrementAndGet();
        String email = "cnrg-authz-" + n + "@example.com";
        em.createNativeQuery(
                        "INSERT INTO users (email, last_name, first_name, display_name, status, "
                                + "is_searchable, handle_searchable, contact_approval_required, "
                                + "online_visibility, dm_receive_from, encryption_key_version, "
                                + "locale, timezone, reporting_restricted, follow_list_visibility, "
                                + "care_notification_enabled, offline_only, created_at, updated_at) "
                                + "VALUES (:email, 'CNRG', :fn, :dn, 'ACTIVE', 1, 1, 1, "
                                + "'NOBODY', 'ANYONE', 1, 'ja', 'Asia/Tokyo', 0, 'PUBLIC', 1, 0, NOW(), NOW())")
                .setParameter("email", email)
                .setParameter("fn", "利用者" + n)
                .setParameter("dn", "CNRG 利用者" + n)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM users WHERE email = :email")
                .setParameter("email", email).getSingleResult()).longValue();
    }

    private Long insertOrganization() {
        String name = "CNRG組織-" + SEQ.incrementAndGet();
        em.createNativeQuery(
                        "INSERT INTO organizations (name, org_type, visibility, hierarchy_visibility, "
                                + "supporter_enabled, version, slug, created_at, updated_at) "
                                + "VALUES (:name, 'OTHER', 'PUBLIC', 'NONE', 1, 0, "
                                + "CONCAT('cnrg-', LEFT(REPLACE(UUID(),'-',''),8)), NOW(), NOW())")
                .setParameter("name", name)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM organizations WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult()).longValue();
    }

    /** ロール付与＋当該スコープへの ACTIVE membership を同時に作る（checkMembership 系の前提を満たす）。 */
    private void grantRole(Long userId, String roleName, Long orgIdParam) {
        em.createNativeQuery(
                        "INSERT INTO user_roles (user_id, role_id, team_id, organization_id, created_at, updated_at) "
                                + "SELECT :uid, r.id, NULL, :oid, NOW(), NOW() FROM roles r WHERE r.name = :role")
                .setParameter("uid", userId)
                .setParameter("oid", orgIdParam)
                .setParameter("role", roleName)
                .executeUpdate();
        em.createNativeQuery(
                        "INSERT INTO memberships (user_id, scope_type, scope_id, role_kind, joined_at, created_at, updated_at) "
                                + "SELECT :uid, 'ORGANIZATION', :oid, 'MEMBER', NOW(), NOW(), NOW() "
                                + "WHERE NOT EXISTS (SELECT 1 FROM memberships m WHERE m.user_id = :uid "
                                + "AND m.scope_type = 'ORGANIZATION' AND m.scope_id = :oid AND m.left_at IS NULL)")
                .setParameter("uid", userId)
                .setParameter("oid", orgIdParam)
                .executeUpdate();
    }
}
