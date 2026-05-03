package com.mannschaft.app.actionmemo.controller;

import com.mannschaft.app.actionmemo.ActionMemoErrorCode;
import com.mannschaft.app.actionmemo.dto.ActionMemoListResponse;
import com.mannschaft.app.actionmemo.dto.ActionMemoResponse;
import com.mannschaft.app.actionmemo.enums.ActionMemoCategory;
import com.mannschaft.app.actionmemo.service.ActionMemoService;
import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link ActionMemoDashboardController} の MockMvc 結合テスト（F02.5 Phase 4-β）。
 *
 * <ul>
 *   <li>ADMIN ユーザーはメンバーの WORK メモ一覧を取得できる</li>
 *   <li>非 ADMIN ユーザーは 403 が返る</li>
 * </ul>
 */
@WebMvcTest(ActionMemoDashboardController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("ActionMemoDashboardController 結合テスト")
class ActionMemoDashboardControllerTest {

    private static final Long ADMIN_USER_ID = 1L;
    private static final Long TEAM_ID = 10L;
    private static final Long MEMBER_ID = 20L;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ActionMemoService actionMemoService;

    @MockitoBean
    private AuthTokenService authTokenService;

    @MockitoBean
    private UserLocaleCache userLocaleCache;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(ADMIN_USER_ID, null, List.of()));
    }

    @Test
    @DisplayName("ADMIN: WORK メモ一覧を 200 で返す")
    void listMemberMemos_asAdmin_success() throws Exception {
        ActionMemoResponse memo = ActionMemoResponse.builder()
                .id(100L)
                .memoDate(LocalDate.of(2026, 5, 1))
                .content("チーム投稿メモ")
                .category(ActionMemoCategory.WORK)
                .postedTeamId(TEAM_ID)
                .createdAt(LocalDateTime.of(2026, 5, 1, 9, 0))
                .build();
        ActionMemoListResponse response = new ActionMemoListResponse(List.of(memo), null);

        given(actionMemoService.listTeamMemberMemos(eq(TEAM_ID), eq(MEMBER_ID),
                eq(ADMIN_USER_ID), any(), any())).willReturn(response);

        mockMvc.perform(get("/api/v1/teams/{teamId}/members/{memberId}/action-memos",
                        TEAM_ID, MEMBER_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data[0].id").value(100))
                .andExpect(jsonPath("$.data[0].content").value("チーム投稿メモ"))
                .andExpect(jsonPath("$.next_cursor").doesNotExist());
    }

    @Test
    @DisplayName("非 ADMIN: DASHBOARD_FORBIDDEN で 403 が返る")
    void listMemberMemos_notAdmin_forbidden() throws Exception {
        willThrow(new BusinessException(ActionMemoErrorCode.ACTION_MEMO_DASHBOARD_FORBIDDEN))
                .given(actionMemoService).listTeamMemberMemos(any(), any(), any(), any(), any());

        mockMvc.perform(get("/api/v1/teams/{teamId}/members/{memberId}/action-memos",
                        TEAM_ID, MEMBER_ID))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("cursor パラメータ付きリクエストが正常に処理される")
    void listMemberMemos_withCursor_success() throws Exception {
        ActionMemoListResponse response = new ActionMemoListResponse(List.of(), null);

        given(actionMemoService.listTeamMemberMemos(eq(TEAM_ID), eq(MEMBER_ID),
                eq(ADMIN_USER_ID), eq(99L), any())).willReturn(response);

        mockMvc.perform(get("/api/v1/teams/{teamId}/members/{memberId}/action-memos",
                        TEAM_ID, MEMBER_ID)
                        .param("cursor", "99"))
                .andExpect(status().isOk());
    }
}
