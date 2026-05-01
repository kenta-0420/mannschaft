package com.mannschaft.app.actionmemo.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.actionmemo.ActionMemoErrorCode;
import com.mannschaft.app.actionmemo.dto.ActionMemoListResponse;
import com.mannschaft.app.actionmemo.dto.ActionMemoResponse;
import com.mannschaft.app.actionmemo.dto.AvailableTeamResponse;
import com.mannschaft.app.actionmemo.dto.PublishDailyToTeamRequest;
import com.mannschaft.app.actionmemo.dto.PublishDailyToTeamResponse;
import com.mannschaft.app.actionmemo.dto.PublishToTeamRequest;
import com.mannschaft.app.actionmemo.dto.PublishToTeamResponse;
import com.mannschaft.app.actionmemo.enums.ActionMemoCategory;
import com.mannschaft.app.actionmemo.service.ActionMemoService;
import com.mannschaft.app.actionmemo.service.ActionMemoTagService;
import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.proxy.repository.ProxyInputConsentRepository;
import com.mannschaft.app.proxy.ProxyInputContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link ActionMemoController} Phase 3 拡張 API の MockMvc 結合テスト。
 *
 * <p>F02.5 Phase 3 設計書 §10.1 に従い、以下のエンドポイントを検証する:</p>
 * <ul>
 *   <li>{@code POST /api/v1/action-memos/{id}/publish-to-team}</li>
 *   <li>{@code POST /api/v1/action-memos/publish-daily-to-team}</li>
 *   <li>{@code GET  /api/v1/action-memos/available-teams}</li>
 *   <li>{@code GET  /api/v1/action-memos?category=WORK} （Spec drift: 実装は category クエリ未対応）</li>
 * </ul>
 *
 * <p><b>命名理由</b>: 既存の {@code ActionMemoControllerTest} は実態として
 * {@link com.mannschaft.app.actionmemo.ActionMemoRateLimitFilter} のレート検証であり、
 * 既存テストを壊さないため Phase 3 用の Controller テストは別ファイルとして追加する。</p>
 */
@WebMvcTest(ActionMemoController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("ActionMemoController Phase 3 拡張 API 結合テスト")
class ActionMemoControllerPhase3Test {

    private static final Long USER_ID = 1L;
    private static final Long TEAM_ID = 42L;
    private static final Long MEMO_ID = 100L;
    private static final Long TIMELINE_POST_ID = 999L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private ActionMemoService actionMemoService;

    @MockitoBean
    private ActionMemoTagService actionMemoTagService;

    // JwtAuthenticationFilter 依存解決用
    @MockitoBean
    private AuthTokenService authTokenService;

    // F11.3: UserLocaleFilter 依存解決用
    @MockitoBean
    private UserLocaleCache userLocaleCache;

    // F14.1: ProxyInputContextFilter の依存解決用（@WebMvcTest コンテキストで必要）
    @MockitoBean
    private ProxyInputConsentRepository proxyInputConsentRepository;
    @MockitoBean
    private ProxyInputContext proxyInputContext;

    @BeforeEach
    void setUpSecurityContext() {
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                USER_ID.toString(), null, List.of());
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    // ════════════════════════════════════════════════════════════════════
    // POST /api/v1/action-memos/{id}/publish-to-team
    // ════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("POST /api/v1/action-memos/{id}/publish-to-team")
    class PublishToTeam {

        @Test
        @DisplayName("正常系: 201 Created で投稿 ID とチーム ID が返る")
        void publishToTeam_正常系_201() throws Exception {
            PublishToTeamResponse stub = PublishToTeamResponse.builder()
                    .timelinePostId(TIMELINE_POST_ID)
                    .teamId(TEAM_ID)
                    .memoId(MEMO_ID)
                    .build();
            given(actionMemoService.publishToTeam(eq(MEMO_ID), any(PublishToTeamRequest.class), eq(USER_ID)))
                    .willReturn(stub);

            String body = objectMapper.writeValueAsString(
                    new PublishToTeamRequest(TEAM_ID, "午前のレビュー対応、完了しました"));

            mockMvc.perform(post("/api/v1/action-memos/{id}/publish-to-team", MEMO_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.timeline_post_id").value(TIMELINE_POST_ID))
                    .andExpect(jsonPath("$.data.team_id").value(TEAM_ID))
                    .andExpect(jsonPath("$.data.memo_id").value(MEMO_ID));
        }

        @Test
        @DisplayName("PRIVATE メモを投稿しようとすると 400 ACTION_MEMO_015 (only_work_can_be_posted)")
        void publishToTeam_PRIVATEメモ_400() throws Exception {
            willThrow(new BusinessException(ActionMemoErrorCode.ACTION_MEMO_ONLY_WORK_CAN_BE_POSTED))
                    .given(actionMemoService)
                    .publishToTeam(eq(MEMO_ID), any(PublishToTeamRequest.class), eq(USER_ID));

            String body = objectMapper.writeValueAsString(new PublishToTeamRequest(TEAM_ID, null));

            mockMvc.perform(post("/api/v1/action-memos/{id}/publish-to-team", MEMO_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("ACTION_MEMO_015"));
        }

        @Test
        @DisplayName("既投稿メモの再投稿で 409 ACTION_MEMO_016 (already_posted)")
        void publishToTeam_既投稿_409() throws Exception {
            willThrow(new BusinessException(ActionMemoErrorCode.ACTION_MEMO_ALREADY_POSTED))
                    .given(actionMemoService)
                    .publishToTeam(eq(MEMO_ID), any(PublishToTeamRequest.class), eq(USER_ID));

            String body = objectMapper.writeValueAsString(new PublishToTeamRequest(TEAM_ID, null));

            mockMvc.perform(post("/api/v1/action-memos/{id}/publish-to-team", MEMO_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value("ACTION_MEMO_016"));
        }

        @Test
        @DisplayName("チーム未参加で 404 ACTION_MEMO_019 (team_not_found, IDOR 対策)")
        void publishToTeam_チーム未参加_404() throws Exception {
            willThrow(new BusinessException(ActionMemoErrorCode.ACTION_MEMO_TEAM_NOT_FOUND))
                    .given(actionMemoService)
                    .publishToTeam(eq(MEMO_ID), any(PublishToTeamRequest.class), eq(USER_ID));

            String body = objectMapper.writeValueAsString(new PublishToTeamRequest(TEAM_ID, null));

            mockMvc.perform(post("/api/v1/action-memos/{id}/publish-to-team", MEMO_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("ACTION_MEMO_019"));
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // POST /api/v1/action-memos/publish-daily-to-team
    // ════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("POST /api/v1/action-memos/publish-daily-to-team")
    class PublishDailyToTeam {

        @Test
        @DisplayName("正常系: 201 Created で postedCount が返る")
        void publishDailyToTeam_正常系_201() throws Exception {
            PublishDailyToTeamResponse stub = PublishDailyToTeamResponse.builder()
                    .teamId(TEAM_ID)
                    .postedCount(3)
                    .build();
            given(actionMemoService.publishDailyToTeam(any(PublishDailyToTeamRequest.class), eq(USER_ID)))
                    .willReturn(stub);

            String body = objectMapper.writeValueAsString(new PublishDailyToTeamRequest(TEAM_ID));

            mockMvc.perform(post("/api/v1/action-memos/publish-daily-to-team")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.team_id").value(TEAM_ID))
                    .andExpect(jsonPath("$.data.posted_count").value(3));
        }

        @Test
        @DisplayName("当日 WORK メモなしで 400 ACTION_MEMO_018 (no_work_memo_today)")
        void publishDailyToTeam_当日WORK0件_400() throws Exception {
            willThrow(new BusinessException(ActionMemoErrorCode.ACTION_MEMO_NO_WORK_MEMO_TODAY))
                    .given(actionMemoService)
                    .publishDailyToTeam(any(PublishDailyToTeamRequest.class), eq(USER_ID));

            String body = objectMapper.writeValueAsString(new PublishDailyToTeamRequest(TEAM_ID));

            mockMvc.perform(post("/api/v1/action-memos/publish-daily-to-team")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("ACTION_MEMO_018"));
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // GET /api/v1/action-memos/available-teams
    // ════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("GET /api/v1/action-memos/available-teams")
    class GetAvailableTeams {

        @Test
        @DisplayName("正常系: 200 OK で is_default フラグ付きの所属チーム一覧が返る")
        void getAvailableTeams_正常系_200() throws Exception {
            List<AvailableTeamResponse> teams = List.of(
                    AvailableTeamResponse.builder()
                            .id(TEAM_ID)
                            .name("開発チーム")
                            .isDefault(true)
                            .build(),
                    AvailableTeamResponse.builder()
                            .id(TEAM_ID + 1)
                            .name("運用チーム")
                            .isDefault(false)
                            .build());
            given(actionMemoService.getAvailableTeams(USER_ID)).willReturn(teams);

            mockMvc.perform(get("/api/v1/action-memos/available-teams"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[0].id").value(TEAM_ID))
                    .andExpect(jsonPath("$.data[0].name").value("開発チーム"))
                    .andExpect(jsonPath("$.data[0].is_default").value(true))
                    .andExpect(jsonPath("$.data[1].id").value(TEAM_ID + 1))
                    .andExpect(jsonPath("$.data[1].is_default").value(false));
        }

        @Test
        @DisplayName("所属チーム0件: 200 OK で空配列を返す")
        void getAvailableTeams_0件_200() throws Exception {
            given(actionMemoService.getAvailableTeams(USER_ID)).willReturn(List.of());

            mockMvc.perform(get("/api/v1/action-memos/available-teams"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data").isArray())
                    .andExpect(jsonPath("$.data.length()").value(0));
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // GET /api/v1/action-memos?category=WORK （Spec drift 検証）
    // ════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("GET /api/v1/action-memos: category クエリ Spec drift 確認")
    class ListByCategory {

        /**
         * <p><b>Spec drift</b>: 設計書 §6.1 では「クエリパラメータに {@code category=WORK|PRIVATE|OTHER}
         * を追加」とされているが、{@link ActionMemoController#listMemos} および
         * {@link ActionMemoService#listMemos} は category 引数を受け取らず、フィルタが効かない。
         * 本テストは Spring が未知のクエリパラメータを単に無視することを確認しつつ、
         * 200 OK で全件返却される現状を回帰防止する。実装側で category フィルタを追加する際は
         * このテストを「フィルタ後の件数を assert する形」に書き換えること。</p>
         */
        @Test
        @DisplayName("category=WORK 指定: 200 OK（現状は category 未対応のため全件返却）")
        void listMemos_categoryWORK指定_200() throws Exception {
            ActionMemoResponse memo = ActionMemoResponse.builder()
                    .id(MEMO_ID)
                    .memoDate(LocalDate.of(2026, 4, 28))
                    .content("仕事のメモ")
                    .category(ActionMemoCategory.WORK)
                    .build();
            ActionMemoListResponse stub = new ActionMemoListResponse(List.of(memo), null);
            given(actionMemoService.listMemos(
                    eq(USER_ID), any(), any(), any(), any(), any(), any()))
                    .willReturn(stub);

            mockMvc.perform(get("/api/v1/action-memos").param("category", "WORK"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[0].id").value(MEMO_ID))
                    .andExpect(jsonPath("$.data[0].category").value("WORK"));
        }
    }
}
