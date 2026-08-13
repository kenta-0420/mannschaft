package com.mannschaft.app.moderation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 認可根治戦役 Wave3-B3: moderation ドメイン API 契約テスト（試練）。
 *
 * <p>正本: 軍議上奏「moderation（レポート/警告の per-scope BOLA）」。moderation の admin/system-admin
 * 系入口（{@code /api/v1/admin/reports/**} 等）は Wave0 で {@code SecurityConfig} が
 * {@code hasRole("SYSTEM_ADMIN")} で封止済み（グローバルロールのためスコープ越境の余地がない）。
 * 本 IT は SecurityConfig では防げない <b>ユーザー本人スコープの BOLA</b>（自分以外の
 * {@code actionId} を指定して WARNING 再レビュー／自主修正を起票できてしまう IDOR）を対象とする。</p>
 *
 * <p>金型: {@code DigestScopeContractIT}（{@code @AutoConfigureMockMvc(addFilters=false)} + 実 MySQL。
 * 越境 403/404 はアプリ層例外として認可フィルタ無効でも検証できる）。</p>
 *
 * <p>担当スコープ（他は対象外）:</p>
 * <ul>
 *   <li>{@code WarningReReviewService#createReReview}: actionId が呼び出しユーザー自身の
 *       WARNING violation を指すことを検証する（{@code user_violations} 経由）。不一致・不在・
 *       reportId 不一致は同一コード（MODERATION_EXT_001）で 404（存在秘匿・BOLA是正の要）</li>
 *   <li>{@code UserViolationService#selfCorrect}: 既存の所有者検証（{@code selfCorrect} 自体は
 *       Wave3-B3 以前から正しく実装済み）が GEH の 404 マッピング変更後も一貫して機能することを確認</li>
 * </ul>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("moderation ドメイン API 契約テスト（認可根治 Wave3-B3）")
class ModerationScopeContractIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @PersistenceContext
    private EntityManager em;

    private Long ownerUserId;
    private Long otherUserId;
    private Long adminUserId;

    /** 本人(ownerUserId)の WARNING violation。actionId/reportId 所有者検証の正当系フィクスチャ。 */
    private Long ownActionId;
    private Long ownReportId;

    @BeforeEach
    void setUp() {
        ownerUserId = insertUser("mod-authz-owner@example.com");
        otherUserId = insertUser("mod-authz-other@example.com");
        adminUserId = insertUser("mod-authz-admin@example.com");

        ownReportId = insertContentReport(ownerUserId);
        Long ownActionRowId = insertReportAction(ownReportId, adminUserId);
        ownActionId = ownActionRowId;
        insertUserViolation(ownerUserId, ownReportId, ownActionId);

        em.flush();
        em.clear();
    }

    // ═════════════════════════════════════════════════════════════════════
    // WARNING再レビュー依頼(createReReview) — actionId 所有者検証(BOLA是正)
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("WARNING再レビュー依頼(re-review)")
    class ReReview {

        @Test
        @DisplayName("正当: 本人が自分のactionId/reportIdで再レビュー依頼すると201")
        void 本人の再レビュー依頼は201() throws Exception {
            setAuthentication(ownerUserId);

            mockMvc.perform(post("/api/v1/warnings/{actionId}/re-review", ownActionId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(reReviewBody(ownReportId))))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.userId").value(ownerUserId))
                    .andExpect(jsonPath("$.data.actionId").value(ownActionId))
                    .andExpect(jsonPath("$.data.reportId").value(ownReportId));
        }

        @Test
        @DisplayName("越境(BOLA): 他ユーザーのactionIdを指定した再レビュー依頼は404で存在秘匿")
        void 他ユーザーのactionIdを指定すると404() throws Exception {
            setAuthentication(otherUserId); // otherUserId は ownActionId の violation の当事者ではない

            mockMvc.perform(post("/api/v1/warnings/{actionId}/re-review", ownActionId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(reReviewBody(ownReportId))))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("MODERATION_EXT_001"));
        }

        @Test
        @DisplayName("越境(BOLA): 本人のactionIdでも不一致なreportIdを指定した依頼は404で存在秘匿")
        void reportId不一致だと404() throws Exception {
            setAuthentication(ownerUserId);

            // 別の（本人と無関係の）report を用意し、reportId をすり替えて送る
            Long unrelatedReportId = insertContentReport(otherUserId);
            em.flush();

            mockMvc.perform(post("/api/v1/warnings/{actionId}/re-review", ownActionId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(reReviewBody(unrelatedReportId))))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("MODERATION_EXT_001"));
        }

        @Test
        @DisplayName("不在: 存在しないactionIdを指定した依頼は404で存在秘匿")
        void 不在actionIdは404() throws Exception {
            setAuthentication(ownerUserId);

            mockMvc.perform(post("/api/v1/warnings/{actionId}/re-review", 999_999_999L)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(reReviewBody(ownReportId))))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("MODERATION_EXT_001"));
        }

        @Test
        @DisplayName("同一actionIdへの重複依頼はRE_REVIEW_ALREADY_EXISTSで409"
            + "（WarningReReviewService#createReReview の existsByUserIdAndActionId 判定は"
            + "状態競合のため、認可監査Wave6ロットEで ERROR_CODE_STATUS_MAP に 409 を登録した）")
        void 重複依頼はエラー() throws Exception {
            setAuthentication(ownerUserId);

            // 1回目: 成功
            mockMvc.perform(post("/api/v1/warnings/{actionId}/re-review", ownActionId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(reReviewBody(ownReportId))))
                    .andExpect(status().isCreated());

            // 2回目: 重複エラー
            mockMvc.perform(post("/api/v1/warnings/{actionId}/re-review", ownActionId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(reReviewBody(ownReportId))))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value("MODERATION_EXT_007"));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // WARNING自主修正(selfCorrect) — 既存の所有者検証が404マッピング後も機能することを確認
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("WARNING自主修正(self-correct)")
    class SelfCorrect {

        @Test
        @DisplayName("正当: 本人のactionIdで自主修正すると200")
        void 本人の自主修正は200() throws Exception {
            setAuthentication(ownerUserId);

            mockMvc.perform(patch("/api/v1/warnings/{actionId}/self-correct", ownActionId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.isActive").value(false));
        }

        @Test
        @DisplayName("越境(BOLA): 他ユーザーのactionIdで自主修正しようとすると404で存在秘匿")
        void 他ユーザーのactionIdでは404() throws Exception {
            setAuthentication(otherUserId);

            mockMvc.perform(patch("/api/v1/warnings/{actionId}/self-correct", ownActionId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("MODERATION_EXT_001"));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // ヘルパー
    // ═════════════════════════════════════════════════════════════════════

    private void setAuthentication(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
    }

    private Map<String, Object> reReviewBody(Long reportId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("reportId", reportId);
        body.put("reason", "再レビュー理由テスト");
        return body;
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
                                + "VALUES (:email, 'MOD契約', 'テスト', 'MOD契約テスト', 'ACTIVE', "
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

    /**
     * content_reports へ 1 行 INSERT する（NOT NULL 列: target_type/target_id/reported_by/
     * scope_type/scope_id/reason/status/content_hidden/created_at/updated_at をすべて明示）。
     *
     * <p>test プロファイルは {@code spring.jpa.hibernate.ddl-auto=create}（Flyway 無効。
     * {@code backend/src/test/resources/application-test.yml}）でスキーマを生成するため、
     * 実テーブルは {@link com.mannschaft.app.moderation.entity.ContentReportEntity} の
     * {@code @Column} 定義から素直に DDL 化される。{@code contentHidden}
     * （{@code @Column(nullable = false)}）は Java 側で {@code @Builder.Default = false} を
     * 持つのみで DB レベルの DEFAULT 句は生成されない。生 SQL INSERT では明示指定が必須。</p>
     */
    private Long insertContentReport(Long reportedBy) {
        em.createNativeQuery(
                        "INSERT INTO content_reports (target_type, target_id, reported_by, "
                                + "scope_type, scope_id, reason, status, content_hidden, created_at, updated_at) "
                                + "VALUES ('TIMELINE_POST', 1, :reportedBy, "
                                + "'TEAM', 1, 'SPAM', 'PENDING', 0, NOW(), NOW())")
                .setParameter("reportedBy", reportedBy)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT MAX(id) FROM content_reports").getSingleResult()).longValue();
    }

    /**
     * report_actions へ WARNING アクションを 1 行 INSERT する（NOT NULL 列: report_id/action_type/
     * action_by/created_at をすべて明示）。
     */
    private Long insertReportAction(Long reportId, Long actionBy) {
        em.createNativeQuery(
                        "INSERT INTO report_actions (report_id, action_type, action_by, created_at) "
                                + "VALUES (:reportId, 'WARNING', :actionBy, NOW())")
                .setParameter("reportId", reportId)
                .setParameter("actionBy", actionBy)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT MAX(id) FROM report_actions").getSingleResult()).longValue();
    }

    /**
     * user_violations へ WARNING 違反を 1 行 INSERT する（NOT NULL 列: user_id/report_id/action_id/
     * violation_type/reason/is_active/created_at/updated_at をすべて明示）。
     */
    private void insertUserViolation(Long userId, Long reportId, Long actionId) {
        em.createNativeQuery(
                        "INSERT INTO user_violations (user_id, report_id, action_id, violation_type, "
                                + "reason, is_active, created_at, updated_at) "
                                + "VALUES (:userId, :reportId, :actionId, 'WARNING', "
                                + "'GUIDELINE_1_2', 1, NOW(), NOW())")
                .setParameter("userId", userId)
                .setParameter("reportId", reportId)
                .setParameter("actionId", actionId)
                .executeUpdate();
    }
}
