package com.mannschaft.app.tournament.scorekeeper;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.GlobalExceptionHandler;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.tournament.TournamentErrorCode;
import com.mannschaft.app.tournament.scorekeeper.dto.ScorekeeperResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willDoNothing;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link TournamentScorekeeperController} HTTP ステータス契約テスト（F08.7 順位UI 項目③）。
 *
 * <p><b>テスト方針</b>: {@code @EnableMethodSecurity} と {@code @WebMvcTest} の非互換を回避するため
 * {@code MockMvcBuilders.standaloneSetup} + {@code MockedStatic<SecurityUtils>} を用いる。
 * {@link GlobalExceptionHandler} を {@code setControllerAdvice} で直接投入することで、
 * Service が投げた {@link BusinessException} が ERROR_CODE_STATUS_MAP を経由して
 * 設計書通りの HTTP ステータス（403/404）にマッピングされることを実アサートする。</p>
 *
 * <p>これは「enum のみ検証・status code を見ない」という過去の見逃しパターン（前回検分指摘）を根治する。</p>
 *
 * <p><b>検証シナリオ</b>:</p>
 * <ul>
 *   <li>POST/GET/DELETE を正常パスで 201/200/204</li>
 *   <li>非管理者（SCOREKEEPER_MANAGE_FORBIDDEN）→ GlobalExceptionHandler 経由で <b>403</b></li>
 *   <li>不在スコアキーパー削除（SCOREKEEPER_NOT_FOUND）→ <b>404</b></li>
 *   <li>他組織大会指定（TOURNAMENT_NOT_FOUND IDOR 隠蔽）→ <b>404</b></li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TournamentScorekeeperController — HTTP ステータス契約テスト（F08.7 項目③）")
class TournamentScorekeeperControllerTest {

    private static final Long ORG_ID = 100L;
    private static final Long T_ID = 7L;
    private static final Long USER_ID = 1L;

    @Mock
    private TournamentScorekeeperService scorekeeperService;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private MockedStatic<SecurityUtils> securityUtilsMock;

    @BeforeEach
    void setUp() {
        objectMapper.findAndRegisterModules();
        TournamentScorekeeperController controller =
                new TournamentScorekeeperController(scorekeeperService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .setControllerAdvice(new GlobalExceptionHandler(new StaticMessageSource()))
                .build();
        securityUtilsMock = Mockito.mockStatic(SecurityUtils.class);
        securityUtilsMock.when(SecurityUtils::getCurrentUserId).thenReturn(USER_ID);
    }

    @AfterEach
    void tearDown() {
        securityUtilsMock.close();
    }

    // ──────────────────────────────────────────────────────────────────
    // 認可: @PreAuthorize 宣言の存在確認（org admin SpEL ガード番人）
    // ──────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("認可: @PreAuthorize が org admin SpEL ガードで宣言されている")
    class AuthorizationDeclarationTest {

        private static final String ORG_EXPR =
                "@accessGuard.isScopeAdmin(authentication, #orgId, 'ORGANIZATION')";

        @Test
        @DisplayName("GET/POST/DELETE ハンドラはすべて org admin の SpEL ガードを宣言している")
        void allHandlersDeclareOrgScopeGuard() throws NoSuchMethodException {
            assertPreAuthorize("listScorekeepers", ORG_EXPR, Long.class, Long.class);
            assertPreAuthorize("addScorekeeper", ORG_EXPR, Long.class, Long.class,
                    com.mannschaft.app.tournament.scorekeeper.dto.CreateScorekeeperRequest.class);
            assertPreAuthorize("removeScorekeeper", ORG_EXPR, Long.class, Long.class, UUID.class);
        }

        @Test
        @DisplayName("旧 hasRole('ADMIN') 形式の注釈は残っていない（method-security 点火時の一斉403を防止）")
        void noLegacyHasRoleAdminRemains() {
            for (Method m : TournamentScorekeeperController.class.getDeclaredMethods()) {
                PreAuthorize annotation = m.getAnnotation(PreAuthorize.class);
                if (annotation != null) {
                    assertThat(annotation.value())
                            .as("%s に旧 hasRole('ADMIN') が残ると method-security 点火時に 403 になる",
                                    m.getName())
                            .doesNotContain("hasRole('ADMIN')");
                }
            }
        }

        private void assertPreAuthorize(String methodName, String expectedExpr, Class<?>... paramTypes)
                throws NoSuchMethodException {
            Method m = TournamentScorekeeperController.class.getMethod(methodName, paramTypes);
            PreAuthorize annotation = m.getAnnotation(PreAuthorize.class);
            assertThat(annotation)
                    .as("%s に @PreAuthorize が未付与だと非管理者がスコアキーパー管理 API を叩けてしまう",
                            methodName)
                    .isNotNull();
            assertThat(annotation.value())
                    .as("%s は当該 org の管理者のみを許可する SpEL ガードでなければならない", methodName)
                    .isEqualTo(expectedExpr);
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // GET /scorekeepers — 指名一覧
    // ──────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("GET /scorekeepers — 指名一覧")
    class ListScorekeepers {

        @Test
        @DisplayName("200: 正常取得")
        void returns200_whenSuccess() throws Exception {
            ScorekeeperResponse response = new ScorekeeperResponse(
                    UUID.randomUUID(), T_ID, 50L, "佐藤 花子", USER_ID, LocalDateTime.now());
            given(scorekeeperService.listScorekeepers(eq(ORG_ID), eq(T_ID), eq(USER_ID)))
                    .willReturn(List.of(response));

            mockMvc.perform(get("/api/v1/organizations/{orgId}/tournaments/{tId}/scorekeepers",
                            ORG_ID, T_ID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data").isArray())
                    .andExpect(jsonPath("$.data[0].userId").value(50))
                    .andExpect(jsonPath("$.data[0].displayName").value("佐藤 花子"));
        }

        @Test
        @DisplayName("403: 非管理者（SCOREKEEPER_MANAGE_FORBIDDEN）→ GlobalExceptionHandler で 403 に変換")
        void returns403_whenNotOrgAdmin() throws Exception {
            willThrow(new BusinessException(TournamentErrorCode.SCOREKEEPER_MANAGE_FORBIDDEN))
                    .given(scorekeeperService)
                    .listScorekeepers(any(), any(), any());

            mockMvc.perform(get("/api/v1/organizations/{orgId}/tournaments/{tId}/scorekeepers",
                            ORG_ID, T_ID))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("TOUR_059"));
        }

        @Test
        @DisplayName("404: 他組織の大会（TOURNAMENT_NOT_FOUND IDOR 隠蔽）→ 403 ではなく 404")
        void returns404_whenTournamentBelongsToOtherOrg() throws Exception {
            willThrow(new BusinessException(TournamentErrorCode.TOURNAMENT_NOT_FOUND))
                    .given(scorekeeperService)
                    .listScorekeepers(any(), any(), any());

            mockMvc.perform(get("/api/v1/organizations/{orgId}/tournaments/{tId}/scorekeepers",
                            ORG_ID, T_ID))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("TOUR_001"));
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // POST /scorekeepers — 指名追加
    // ──────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /scorekeepers — 指名追加")
    class AddScorekeeper {

        @Test
        @DisplayName("201: 正常追加")
        void returns201_whenSuccess() throws Exception {
            ScorekeeperResponse response = new ScorekeeperResponse(
                    UUID.randomUUID(), T_ID, 50L, "佐藤 花子", USER_ID, LocalDateTime.now());
            given(scorekeeperService.addScorekeeper(eq(ORG_ID), eq(T_ID), eq(USER_ID), eq(50L)))
                    .willReturn(response);

            mockMvc.perform(post("/api/v1/organizations/{orgId}/tournaments/{tId}/scorekeepers",
                            ORG_ID, T_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"userId\": 50}"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.userId").value(50))
                    .andExpect(jsonPath("$.data.displayName").value("佐藤 花子"));
        }

        @Test
        @DisplayName("403: 非管理者（SCOREKEEPER_MANAGE_FORBIDDEN）→ GlobalExceptionHandler で 403 に変換")
        void returns403_whenNotOrgAdmin() throws Exception {
            willThrow(new BusinessException(TournamentErrorCode.SCOREKEEPER_MANAGE_FORBIDDEN))
                    .given(scorekeeperService)
                    .addScorekeeper(any(), any(), any(), any());

            mockMvc.perform(post("/api/v1/organizations/{orgId}/tournaments/{tId}/scorekeepers",
                            ORG_ID, T_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"userId\": 50}"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("TOUR_059"));
        }

        @Test
        @DisplayName("404: 他組織の大会（TOURNAMENT_NOT_FOUND IDOR 隠蔽）→ 403 ではなく 404")
        void returns404_whenTournamentBelongsToOtherOrg() throws Exception {
            willThrow(new BusinessException(TournamentErrorCode.TOURNAMENT_NOT_FOUND))
                    .given(scorekeeperService)
                    .addScorekeeper(any(), any(), any(), any());

            mockMvc.perform(post("/api/v1/organizations/{orgId}/tournaments/{tId}/scorekeepers",
                            ORG_ID, T_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"userId\": 50}"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("TOUR_001"));
        }

        @Test
        @DisplayName("400: userId が null の場合は Bean Validation で 400")
        void returns400_whenUserIdIsNull() throws Exception {
            mockMvc.perform(post("/api/v1/organizations/{orgId}/tournaments/{tId}/scorekeepers",
                            ORG_ID, T_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"userId\": null}"))
                    .andExpect(status().isBadRequest());
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // DELETE /scorekeepers/{skId} — 指名解除
    // ──────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("DELETE /scorekeepers/{skId} — 指名解除")
    class RemoveScorekeeper {

        @Test
        @DisplayName("204: 正常削除")
        void returns204_whenSuccess() throws Exception {
            UUID skId = UUID.randomUUID();
            willDoNothing().given(scorekeeperService)
                    .removeScorekeeper(eq(ORG_ID), eq(T_ID), eq(USER_ID), eq(skId));

            mockMvc.perform(delete(
                            "/api/v1/organizations/{orgId}/tournaments/{tId}/scorekeepers/{skId}",
                            ORG_ID, T_ID, skId))
                    .andExpect(status().isNoContent());
        }

        @Test
        @DisplayName("404: 不在スコアキーパー削除（SCOREKEEPER_NOT_FOUND）→ GlobalExceptionHandler で 404 に変換")
        void returns404_whenScorekeeperNotFound() throws Exception {
            UUID skId = UUID.randomUUID();
            willThrow(new BusinessException(TournamentErrorCode.SCOREKEEPER_NOT_FOUND))
                    .given(scorekeeperService)
                    .removeScorekeeper(any(), any(), any(), any());

            mockMvc.perform(delete(
                            "/api/v1/organizations/{orgId}/tournaments/{tId}/scorekeepers/{skId}",
                            ORG_ID, T_ID, skId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("TOUR_060"));
        }

        @Test
        @DisplayName("403: 非管理者（SCOREKEEPER_MANAGE_FORBIDDEN）→ GlobalExceptionHandler で 403 に変換")
        void returns403_whenNotOrgAdmin() throws Exception {
            UUID skId = UUID.randomUUID();
            willThrow(new BusinessException(TournamentErrorCode.SCOREKEEPER_MANAGE_FORBIDDEN))
                    .given(scorekeeperService)
                    .removeScorekeeper(any(), any(), any(), any());

            mockMvc.perform(delete(
                            "/api/v1/organizations/{orgId}/tournaments/{tId}/scorekeepers/{skId}",
                            ORG_ID, T_ID, skId))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("TOUR_059"));
        }

        @Test
        @DisplayName("404: 他組織の大会（TOURNAMENT_NOT_FOUND IDOR 隠蔽）→ 403 ではなく 404")
        void returns404_whenTournamentBelongsToOtherOrg() throws Exception {
            UUID skId = UUID.randomUUID();
            willThrow(new BusinessException(TournamentErrorCode.TOURNAMENT_NOT_FOUND))
                    .given(scorekeeperService)
                    .removeScorekeeper(any(), any(), any(), any());

            mockMvc.perform(delete(
                            "/api/v1/organizations/{orgId}/tournaments/{tId}/scorekeepers/{skId}",
                            ORG_ID, T_ID, skId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("TOUR_001"));
        }
    }
}
