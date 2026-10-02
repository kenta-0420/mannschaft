package com.mannschaft.app.school.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.common.security.AccessGuard;
import com.mannschaft.app.proxy.ProxyInputContext;
import com.mannschaft.app.proxy.repository.ProxyInputConsentRepository;
import com.mannschaft.app.school.dto.DisclosureRequest;
import com.mannschaft.app.school.dto.DisclosureResponse;
import com.mannschaft.app.school.dto.WithholdRequest;
import com.mannschaft.app.school.entity.AttendanceDisclosureRecordEntity.DisclosureMode;
import com.mannschaft.app.school.entity.AttendanceDisclosureRecordEntity.DisclosureRecipients;
import com.mannschaft.app.school.service.DisclosureService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link AttendanceDisclosureController} の MockMvc 結合テスト（認可根治 Phase 4 / F03.13 Phase 15）。
 *
 * <p><b>認可テストの実装方針（@EnableMethodSecurity 有効化後）:</b></p>
 * <ul>
 *   <li><b>200/201（TEAM_ADMIN アクセス可）</b>: {@link AccessGuard#isScopeAdmin} が {@code true} を
 *       返すようにモックして、ハンドラが想定レスポンスを返すことを検証する。</li>
 *   <li><b>403（非 ADMIN による拒否）</b>: {@code @PreAuthorize("@accessGuard.isScopeAdmin(...)")}
 *       アノテーションが各ハンドラに宣言されていることを Reflection で検証する。
 *       {@code @EnableMethodSecurity} 有効化により、{@link AccessGuard#isScopeAdmin} が
 *       {@code false} を返すと Spring Security が自動的に 403 を返す。</li>
 *   <li><b>401（未認証の拒否）</b>: HTTP 層の {@code anyRequest().authenticated()} が担保し、
 *       {@code SecurityConfigAuthorizationTest} が deny-by-default を別途検証済み。
 *       本テストでは {@code addFilters = false} のため認証フィルターを通さない。</li>
 * </ul>
 */
@WebMvcTest(AttendanceDisclosureController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("AttendanceDisclosureController 結合テスト（Phase 4 認可確認）")
class AttendanceDisclosureControllerTest {

    private static final Long USER_ID       = 100L;
    private static final Long TEAM_ID       = 10L;
    private static final Long EVALUATION_ID = 50L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private DisclosureService disclosureService;

    @MockitoBean
    private AuthTokenService authTokenService;

    @MockitoBean
    private UserLocaleCache userLocaleCache;

    /** F14.1: ProxyInputContextFilter の依存解決用（@WebMvcTest コンテキストで JPA ロード防止） */
    @MockitoBean
    private ProxyInputConsentRepository proxyInputConsentRepository;

    @MockitoBean
    private ProxyInputContext proxyInputContext;

    /**
     * @WebMvcTest コンテキスト用: @EnableMethodSecurity 有効化後の SpEL ガード依存解決。
     * {@code @PreAuthorize("@accessGuard.isScopeAdmin(...)")} の評価に必要。
     */
    @MockitoBean
    private AccessGuard accessGuard;

    @BeforeEach
    void setUpSecurityContext() {
        // SecurityUtils.getCurrentUserId() がユーザーIDを取得できるようにセット
        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(USER_ID.toString(), null, List.of());
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @AfterEach
    void tearDownSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    // ════════════════════════════════════════════════════════════
    // POST /api/v1/teams/{teamId}/attendance/requirements/evaluations/{evaluationId}/disclose
    // ════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("POST /disclose — 開示判断エンドポイント")
    class Disclose {

        private DisclosureRequest validRequest() {
            return new DisclosureRequest(DisclosureMode.WITH_NUMBERS, DisclosureRecipients.BOTH, "テストメッセージ");
        }

        private DisclosureResponse stubResponse() {
            return new DisclosureResponse(
                    1L, EVALUATION_ID, 200L,
                    "DISCLOSE", "WITH_NUMBERS", "BOTH",
                    "テストメッセージ", USER_ID, LocalDateTime.of(2026, 6, 1, 10, 0));
        }

        @Test
        @DisplayName("TEAM_ADMIN（isScopeAdmin=true） → 201 Created")
        void teamAdmin_201() throws Exception {
            given(accessGuard.isScopeAdmin(any(), eq(TEAM_ID), eq("TEAM"))).willReturn(true);
            given(disclosureService.disclose(eq(TEAM_ID), eq(EVALUATION_ID), any(), eq(USER_ID)))
                    .willReturn(stubResponse());

            mockMvc.perform(post(
                                "/api/v1/teams/{teamId}/attendance/requirements/evaluations/{evaluationId}/disclose",
                                TEAM_ID, EVALUATION_ID)
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(validRequest())))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.evaluationId").value(EVALUATION_ID))
                    .andExpect(jsonPath("$.data.mode").value("WITH_NUMBERS"));
        }

        @Test
        @DisplayName("@PreAuthorize に @accessGuard.isScopeAdmin 宣言あり（非 ADMIN は @EnableMethodSecurity により 403 返却）")
        void disclose_hasPreAuthorizeAnnotation() throws NoSuchMethodException {
            Method method = AttendanceDisclosureController.class.getMethod(
                    "disclose", Long.class, Long.class, DisclosureRequest.class);

            PreAuthorize annotation = method.getAnnotation(PreAuthorize.class);

            assertThat(annotation)
                    .as("disclose に @PreAuthorize が付与されていること")
                    .isNotNull();
            assertThat(annotation.value())
                    .as("TEAM スコープ ADMIN を @accessGuard.isScopeAdmin で確認する SpEL であること")
                    .contains("accessGuard.isScopeAdmin")
                    .contains("TEAM");
        }

        @Test
        @DisplayName("バリデーション: mode が null → 400")
        void validate_nullMode_400() throws Exception {
            // mode を省いた不正リクエスト
            String badBody = "{\"recipients\":\"BOTH\"}";

            mockMvc.perform(post(
                                "/api/v1/teams/{teamId}/attendance/requirements/evaluations/{evaluationId}/disclose",
                                TEAM_ID, EVALUATION_ID)
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(badBody))
                    .andExpect(status().isBadRequest());
        }
    }

    // ════════════════════════════════════════════════════════════
    // POST /api/v1/teams/{teamId}/attendance/requirements/evaluations/{evaluationId}/withhold
    // ════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("POST /withhold — 非開示判断エンドポイント")
    class Withhold {

        private WithholdRequest validRequest() {
            return new WithholdRequest("現時点で開示不適切");
        }

        private DisclosureResponse stubResponse() {
            return new DisclosureResponse(
                    2L, EVALUATION_ID, 200L,
                    "WITHHOLD", null, null,
                    null, USER_ID, LocalDateTime.of(2026, 6, 1, 11, 0));
        }

        @Test
        @DisplayName("TEAM_ADMIN（isScopeAdmin=true） → 201 Created")
        void teamAdmin_201() throws Exception {
            given(accessGuard.isScopeAdmin(any(), eq(TEAM_ID), eq("TEAM"))).willReturn(true);
            given(disclosureService.withhold(eq(TEAM_ID), eq(EVALUATION_ID), any(), eq(USER_ID)))
                    .willReturn(stubResponse());

            mockMvc.perform(post(
                                "/api/v1/teams/{teamId}/attendance/requirements/evaluations/{evaluationId}/withhold",
                                TEAM_ID, EVALUATION_ID)
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(validRequest())))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.decision").value("WITHHOLD"));
        }

        @Test
        @DisplayName("@PreAuthorize に @accessGuard.isScopeAdmin 宣言あり（非 ADMIN は @EnableMethodSecurity により 403 返却）")
        void withhold_hasPreAuthorizeAnnotation() throws NoSuchMethodException {
            Method method = AttendanceDisclosureController.class.getMethod(
                    "withhold", Long.class, Long.class, WithholdRequest.class);

            PreAuthorize annotation = method.getAnnotation(PreAuthorize.class);

            assertThat(annotation)
                    .as("withhold に @PreAuthorize が付与されていること")
                    .isNotNull();
            assertThat(annotation.value())
                    .as("TEAM スコープ ADMIN を @accessGuard.isScopeAdmin で確認する SpEL であること")
                    .contains("accessGuard.isScopeAdmin")
                    .contains("TEAM");
        }

        @Test
        @DisplayName("バリデーション: withholdReason が空文字 → 400")
        void validate_emptyReason_400() throws Exception {
            String badBody = "{\"withholdReason\":\"\"}";

            mockMvc.perform(post(
                                "/api/v1/teams/{teamId}/attendance/requirements/evaluations/{evaluationId}/withhold",
                                TEAM_ID, EVALUATION_ID)
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(badBody))
                    .andExpect(status().isBadRequest());
        }
    }

    // ════════════════════════════════════════════════════════════
    // GET /api/v1/teams/{teamId}/attendance/requirements/evaluations/{evaluationId}/disclosure-history
    // ════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("GET /disclosure-history — 履歴取得エンドポイント")
    class DisclosureHistory {

        @Test
        @DisplayName("TEAM_ADMIN（isScopeAdmin=true） → 200 OK + 空リスト")
        void teamAdmin_200() throws Exception {
            given(accessGuard.isScopeAdmin(any(), eq(TEAM_ID), eq("TEAM"))).willReturn(true);
            given(disclosureService.getDisclosureHistory(eq(TEAM_ID), eq(EVALUATION_ID), eq(USER_ID)))
                    .willReturn(List.of());

            mockMvc.perform(get(
                                "/api/v1/teams/{teamId}/attendance/requirements/evaluations/{evaluationId}/disclosure-history",
                                TEAM_ID, EVALUATION_ID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data").isArray())
                    .andExpect(jsonPath("$.data").isEmpty());
        }

        @Test
        @DisplayName("@PreAuthorize に @accessGuard.isScopeAdmin 宣言あり（非 ADMIN は @EnableMethodSecurity により 403 返却）")
        void getDisclosureHistory_hasPreAuthorizeAnnotation() throws NoSuchMethodException {
            Method method = AttendanceDisclosureController.class.getMethod(
                    "getDisclosureHistory", Long.class, Long.class);

            PreAuthorize annotation = method.getAnnotation(PreAuthorize.class);

            assertThat(annotation)
                    .as("getDisclosureHistory に @PreAuthorize が付与されていること")
                    .isNotNull();
            assertThat(annotation.value())
                    .as("TEAM スコープ ADMIN を @accessGuard.isScopeAdmin で確認する SpEL であること")
                    .contains("accessGuard.isScopeAdmin")
                    .contains("TEAM");
        }
    }

    // ════════════════════════════════════════════════════════════
    // GET /me/attendance/requirements/disclosed
    // AttendanceDisclosureController#getDisclosedEvaluationsForMe の自己スコープ性を固定する契約テスト。
    // ════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("GET /me/attendance/requirements/disclosed — 自己スコープ契約テスト")
    class DisclosedEvaluationsForMe {

        @Test
        @DisplayName("getDisclosedEvaluationsForMe は SecurityUtils.getCurrentUserId() のみを検索条件に渡す")
        void getDisclosedEvaluationsForMe_boundToCurrentUserOnly() throws Exception {
            given(disclosureService.getDisclosedEvaluationsForUser(USER_ID)).willReturn(List.of());

            mockMvc.perform(get("/api/v1/me/attendance/requirements/disclosed"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data").isArray());

            // 他人の userId を検索条件に渡す経路が存在しないことの裏取り。
            org.mockito.Mockito.verify(disclosureService).getDisclosedEvaluationsForUser(USER_ID);
        }
    }
}
