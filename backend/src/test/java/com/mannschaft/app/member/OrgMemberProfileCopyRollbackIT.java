package com.mannschaft.app.member;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * PR #3387 試練 AC-27（途中失敗・実 DB）: copy-members で複数件をコピーする途中、2件目以降の INSERT が
 * 失敗したら、先に保存した行もロールバックされ、コピー先は0件のままであることを確かめる。
 *
 * <p>ロールバックを観測するため、このクラスはテスト側のトランザクションを張らない（{@code @Transactional}
 * を付けると、サービスの書き込みがテストのトランザクションに合流し、失敗後も同じトランザクション内から
 * 先の行が見えてしまう）。フィクスチャは JdbcTemplate で確定させ、{@link #cleanUp()} で消す。</p>
 *
 * <p>2件目の INSERT だけを失敗させるため、コピー先ページ宛ての特定の表示名に対して SIGNAL する
 * BEFORE INSERT トリガーを一時的に張る。トリガー作成は SUPER 相当の権限が要るため（binlog 有効時）、
 * Testcontainers の root（パスワードは同じ）で接続して作成・削除する。共有コンテナのため必ず後始末する。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("PR #3387 試練 AC-27: copy-members の途中失敗は全件ロールバック（実 DB）")
class OrgMemberProfileCopyRollbackIT extends AbstractMySqlIntegrationTest {

    private static final String TRIGGER = "trg_ac27_copy_fail";
    private static final String BOOM = "AC27 BOOM";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbc;

    private final String nonce = UUID.randomUUID().toString().substring(0, 8);
    private Long orgId;
    private Long adminId;
    private Long sourcePageId;
    private Long targetPageId;

    @BeforeEach
    void setUp() throws Exception {
        seedRole("ADMIN", 2);
        seedRole("MEMBER", 4);

        jdbc.update("INSERT INTO organizations (name, org_type, visibility, hierarchy_visibility, "
                + "supporter_enabled, version, slug, created_at, updated_at) "
                + "VALUES (?, 'OTHER', 'PUBLIC', 'NONE', 1, 0, ?, NOW(), NOW())",
                "AC27組織" + nonce, "ac27-" + nonce);
        orgId = jdbc.queryForObject("SELECT id FROM organizations WHERE name = ?", Long.class, "AC27組織" + nonce);

        String email = "ac27-" + nonce + "@example.com";
        jdbc.update("INSERT INTO users (email, last_name, first_name, display_name, status, "
                + "is_searchable, handle_searchable, contact_approval_required, "
                + "online_visibility, dm_receive_from, encryption_key_version, "
                + "locale, timezone, reporting_restricted, follow_list_visibility, "
                + "care_notification_enabled, offline_only, created_at, updated_at) "
                + "VALUES (?, 'AC27', 'テスト', 'AC27 テスト', 'ACTIVE', 1, 1, 1, "
                + "'NOBODY', 'ANYONE', 1, 'ja', 'Asia/Tokyo', 0, 'PUBLIC', 1, 0, NOW(), NOW())", email);
        adminId = jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);

        // ADMIN は user_roles、所属は memberships（別系統のため両方張る。MemberScopeContractIT:121 の地雷）
        jdbc.update("INSERT INTO user_roles (user_id, role_id, team_id, organization_id, created_at, updated_at) "
                + "SELECT ?, r.id, NULL, ?, NOW(), NOW() FROM roles r WHERE r.name = 'ADMIN'", adminId, orgId);
        jdbc.update("INSERT INTO memberships (user_id, scope_type, scope_id, role_kind, joined_at, "
                + "created_at, updated_at) VALUES (?, 'ORGANIZATION', ?, 'MEMBER', NOW(), NOW(), NOW())",
                adminId, orgId);

        sourcePageId = insertPage("AC27 コピー元", "PUBLISHED");
        targetPageId = insertPage("AC27 コピー先", "DRAFT");
        insertProfile(sourcePageId, "AC27 一件目", 0);
        insertProfile(sourcePageId, BOOM, 1);

        try (Connection root = DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword());
             Statement st = root.createStatement()) {
            st.execute("DROP TRIGGER IF EXISTS " + TRIGGER);
            st.execute("CREATE TRIGGER " + TRIGGER + " BEFORE INSERT ON member_profiles FOR EACH ROW "
                    + "BEGIN IF NEW.team_page_id = " + targetPageId + " AND NEW.display_name = '" + BOOM + "' THEN "
                    + "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'AC27 injected failure'; END IF; END");
        }
    }

    @AfterEach
    void cleanUp() throws Exception {
        SecurityContextHolder.clearContext();
        try (Connection root = DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword());
             Statement st = root.createStatement()) {
            st.execute("DROP TRIGGER IF EXISTS " + TRIGGER);
        }
        jdbc.update("DELETE FROM member_profiles WHERE team_page_id IN (?, ?)", sourcePageId, targetPageId);
        jdbc.update("DELETE FROM team_pages WHERE id IN (?, ?)", sourcePageId, targetPageId);
        jdbc.update("DELETE FROM memberships WHERE user_id = ?", adminId);
        jdbc.update("DELETE FROM user_roles WHERE user_id = ?", adminId);
        jdbc.update("DELETE FROM users WHERE id = ?", adminId);
        jdbc.update("DELETE FROM organizations WHERE id = ?", orgId);
    }

    @Test
    @DisplayName("AC-27: 2件目の INSERT が失敗したら、1件目もロールバックされコピー先は0件のまま")
    void AC27_途中失敗は全件ロールバック() throws Exception {
        assertThat(countProfiles(sourcePageId)).as("前提: コピー元に表示中2件").isEqualTo(2);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(adminId.toString(), null, List.of()));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sourcePageId", sourcePageId);

        Integer status = null;
        Throwable thrown = null;
        try {
            status = mockMvc.perform(post("/api/v1/team/pages/{id}/copy-members", targetPageId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body)))
                    .andReturn().getResponse().getStatus();
        } catch (Exception e) {
            thrown = e;
        }

        assertThat(thrown != null || (status != null && status >= 400))
                .as("前提: 注入した失敗でリクエストが失敗していること（status=" + status + ", thrown=" + thrown + "）")
                .isTrue();
        assertThat(countProfiles(targetPageId)).as("1件目も含めてロールバックされていること").isZero();
    }

    private long countProfiles(Long pageId) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM member_profiles WHERE team_page_id = ?", Long.class, pageId);
        return count == null ? 0 : count;
    }

    private void seedRole(String name, int priority) {
        jdbc.update("INSERT IGNORE INTO roles (name, display_name, priority, is_system, created_at, updated_at) "
                + "VALUES (?, ?, ?, 1, NOW(), NOW())", name, name, priority);
    }

    private Long insertPage(String title, String status) {
        String slug = "ac27-" + UUID.randomUUID().toString().substring(0, 12);
        jdbc.update("INSERT INTO team_pages (organization_id, title, slug, page_type, visibility, status, "
                + "allow_self_edit, sort_order, created_at, updated_at) "
                + "VALUES (?, ?, ?, 'YEARLY', 'MEMBERS_ONLY', ?, 0, 0, NOW(), NOW())",
                orgId, title, slug, status);
        return jdbc.queryForObject("SELECT id FROM team_pages WHERE slug = ?", Long.class, slug);
    }

    private void insertProfile(Long pageId, String displayName, int sortOrder) {
        jdbc.update("INSERT INTO member_profiles (team_page_id, display_name, sort_order, is_visible, "
                + "created_at, updated_at) VALUES (?, ?, ?, 1, NOW(), NOW())", pageId, displayName, sortOrder);
    }
}
