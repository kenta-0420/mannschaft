package com.mannschaft.app.common.duplicatename;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;

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
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("柱③-A 同名確認フロー 実経路(HTTP 409)統合テスト")
class DuplicateNameHttpConflictRedIT extends AbstractMySqlIntegrationTest {

    private static final String ENDPOINT = "/api/v1/organizations";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @PersistenceContext
    private EntityManager em;

    private Long userId;

    @BeforeEach
    void setUp() {
        userId = insertUser("dupname-http-it-" + System.nanoTime() + "@example.com");
        ensureRole("ADMIN");
        ensureRole("MEMBER");
        setAuth(userId);
        em.flush();
        em.clear();
    }

    @Test
    @DisplayName("実経路: 同名で未確認なら409(DUPNAME_001)。候補一覧・fingerprintを含む本文が返る")
    void duplicateNameReturns409WithFingerprintAndCandidates() throws Exception {
        String name = "実経路重複IT組織" + System.nanoTime();

        // 1回目: 新規作成なので201
        mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(name, false, null)))
                .andExpect(status().isCreated());

        // 2回目: 同名・未確認なので409
        MvcResult conflict = mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(name, false, null)))
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
        assertThat(visibleCandidates.get(0).at("/name").asText()).isEqualTo(name);

        // 3回目: 同じ fingerprint を返送して confirmDuplicate=true なら201で作成できる
        mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(name, true, fingerprint)))
                .andExpect(status().isCreated());

        assertThat(countOrganizationsByName(name)).isEqualTo(2);
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
        try {
            em.createNativeQuery("SELECT id FROM roles WHERE name = :name")
                    .setParameter("name", roleName)
                    .getSingleResult();
        } catch (NoResultException e) {
            em.createNativeQuery(
                            "INSERT INTO roles (name, display_name, priority, is_system, created_at, updated_at) "
                                    + "VALUES (:name, :name, 99, 0, NOW(), NOW())")
                    .setParameter("name", roleName)
                    .executeUpdate();
        }
    }

    private Long insertUser(String email) {
        em.createNativeQuery(
                        "INSERT INTO users ("
                                + "email, last_name, first_name, display_name, status, "
                                + "is_searchable, handle_searchable, contact_approval_required, "
                                + "online_visibility, dm_receive_from, encryption_key_version, "
                                + "locale, timezone, reporting_restricted, follow_list_visibility, "
                                + "care_notification_enabled, offline_only, "
                                + "created_at, updated_at) "
                                + "VALUES (:email, 'DUPNAME', 'テスト', 'DUPNAME テスト', 'ACTIVE', "
                                + "1, 1, 1, "
                                + "'NOBODY', 'ANYONE', 1, "
                                + "'ja', 'Asia/Tokyo', 0, 'PUBLIC', "
                                + "1, 0, "
                                + "NOW(), NOW())")
                .setParameter("email", email)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM users WHERE email = :email")
                .setParameter("email", email)
                .getSingleResult()).longValue();
    }

    private long countOrganizationsByName(String name) {
        em.flush();
        em.clear();
        return ((Number) em.createNativeQuery(
                        "SELECT COUNT(*) FROM organizations WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult()).longValue();
    }
}
