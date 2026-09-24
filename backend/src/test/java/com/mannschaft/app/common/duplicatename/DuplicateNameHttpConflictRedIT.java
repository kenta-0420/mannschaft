package com.mannschaft.app.common.duplicatename;

import com.fasterxml.jackson.databind.JsonNode;
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
import org.springframework.test.web.servlet.MvcResult;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CMP-260901-1538 柱③-A 検分P2-5是正: 実際の作成サービス（Guard/FingerprintService は
 * モックなしの実 Bean）から HTTP 409 応答までの経路を実 DB で検証する。
 *
 * <p>{@code OrganizationServiceTest} 等の単体テストは {@code DuplicateNameGuardService} を
 * モックしているため、fingerprint 検証・候補再計算・アドバイザリロックの実体を通らない。
 * 本 IT はコントローラ〜サービス〜Guard〜FingerprintService〜Repository の全層を実 Bean で通し、
 * HTTP レベルの契約（409 ボディに {@code fingerprint}・{@code visibleCandidates} が載ること、
 * 正しい fingerprint を返送すれば 201 で作成できること）を検証する。</p>
 *
 * <p><b>検分第2巡是正: クラス単位の {@code @Transactional} を使わない</b>。
 * {@link DuplicateNameGuardServiceImpl} はアドバイザリロックの解放をトランザクション完了
 * （{@code afterCompletion}）まで遅延させる設計に是正済みのため、1テストメソッド全体を
 * 1つの外側トランザクションで包む Spring テストの {@code @Transactional} ロールバック方式では、
 * 同一テストメソッド内で行う複数回の POST（＝本来は別々の HTTP リクエスト＝別々のトランザクション
 * であるべきもの）が実質 1 トランザクションに畳まれてしまい、1 回目の作成で取得したロックが
 * 2 回目の呼び出し時点でまだ解放されず {@code GET_LOCK} がタイムアウトして {@code DUPNAME_002}
 * になってしまう（本番の「リクエストごとに独立したトランザクション」を正しく再現できない）。
 * そのため本 IT は実際に個々の HTTP リクエストを独立にコミットさせ、{@link JdbcTemplate} による
 * 手動セットアップ・後始末で対応する。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("柱③-A 同名確認フロー 実経路(HTTP 409)統合テスト")
class DuplicateNameHttpConflictRedIT extends AbstractMySqlIntegrationTest {

    private static final String ENDPOINT = "/api/v1/organizations";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long userId;
    private String organizationName;

    @BeforeEach
    void setUp() {
        userId = insertUser("dupname-http-it-" + System.nanoTime() + "@example.com");
        ensureRole("ADMIN");
        ensureRole("MEMBER");
        setAuth(userId);
    }

    @AfterEach
    void tearDown() {
        if (organizationName != null) {
            jdbcTemplate.update("DELETE FROM organizations WHERE name = ?", organizationName);
        }
        if (userId != null) {
            jdbcTemplate.update("DELETE FROM memberships WHERE user_id = ?", userId);
            jdbcTemplate.update("DELETE FROM user_roles WHERE user_id = ?", userId);
            jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);
        }
    }

    @Test
    @DisplayName("実経路: 同名で未確認なら409(DUPNAME_001)。候補一覧・fingerprintを含む本文が返る")
    void duplicateNameReturns409WithFingerprintAndCandidates() throws Exception {
        organizationName = "実経路重複IT組織" + System.nanoTime();

        // 1回目: 新規作成なので201（このリクエストのトランザクションは commit 済み。
        // アドバイザリロックも afterCompletion で解放されている）。
        mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(organizationName, false, null)))
                .andExpect(status().isCreated());

        // 2回目: 同名・未確認なので409（別リクエスト＝別トランザクションのため、
        // 1回目のロックは既に解放されており GET_LOCK は即座に成功する）。
        MvcResult conflict = mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(organizationName, false, null)))
                .andExpect(status().isConflict())
                .andReturn();

        JsonNode json = objectMapper.readTree(conflict.getResponse().getContentAsString());
        assertThat(json.at("/error/code").asText()).isEqualTo("DUPNAME_001");
        JsonNode details = json.at("/error/details");
        String fingerprint = details.at("/fingerprint").asText();
        assertThat(fingerprint).isNotBlank();
        JsonNode visibleCandidates = details.at("/visibleCandidates");
        assertThat(visibleCandidates.isArray()).isTrue();
        assertThat(visibleCandidates).hasSize(1);
        assertThat(visibleCandidates.get(0).at("/name").asText()).isEqualTo(organizationName);

        // 3回目: 同じ fingerprint を返送して confirmDuplicate=true なら201で作成できる
        mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(organizationName, true, fingerprint)))
                .andExpect(status().isCreated());

        assertThat(countOrganizationsByName(organizationName)).isEqualTo(2);
    }

    private String body(String name, boolean confirmDuplicate, String fingerprint) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("name", name);
        payload.put("orgType", "OTHER");
        payload.put("visibility", "PUBLIC");
        payload.put("confirmDuplicate", confirmDuplicate);
        payload.put("duplicateNameFingerprint", fingerprint);
        return objectMapper.writeValueAsString(payload);
    }

    private void setAuth(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
    }

    private void ensureRole(String roleName) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM roles WHERE name = ?", Long.class, roleName);
        if (count == null || count == 0) {
            jdbcTemplate.update(
                    "INSERT INTO roles (name, display_name, priority, is_system, created_at, updated_at) "
                            + "VALUES (?, ?, 99, 0, NOW(), NOW())",
                    roleName, roleName);
        }
    }

    private Long insertUser(String email) {
        jdbcTemplate.update(
                "INSERT INTO users ("
                        + "email, last_name, first_name, display_name, status, "
                        + "is_searchable, handle_searchable, contact_approval_required, "
                        + "online_visibility, dm_receive_from, encryption_key_version, "
                        + "locale, timezone, reporting_restricted, follow_list_visibility, "
                        + "care_notification_enabled, offline_only, "
                        + "created_at, updated_at) "
                        + "VALUES (?, 'DUPNAME', 'テスト', 'DUPNAME テスト', 'ACTIVE', "
                        + "1, 1, 1, "
                        + "'NOBODY', 'ANYONE', 1, "
                        + "'ja', 'Asia/Tokyo', 0, 'PUBLIC', "
                        + "1, 0, "
                        + "NOW(), NOW())",
                email);
        return jdbcTemplate.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
    }

    private long countOrganizationsByName(String name) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM organizations WHERE name = ?", Long.class, name);
        return count == null ? 0 : count;
    }
}
