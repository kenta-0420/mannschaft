package com.mannschaft.app.social.announcement;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.common.security.AccessGuard;
import com.mannschaft.app.proxy.ProxyInputContext;
import com.mannschaft.app.proxy.repository.ProxyInputConsentRepository;
import com.mannschaft.app.social.announcement.controller.AnnouncementBroadcastController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link AnnouncementBroadcastController} の MockMvc 結合テスト（F02.8）。
 *
 * <p>告知ウィザードのリクエスト/レスポンスが camelCase で授受されることを契約として検証する
 * （バグ1根治の回帰防止）。サービス層はモックし、JSON の入出力キーに焦点を当てる。</p>
 */
@WebMvcTest(AnnouncementBroadcastController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("AnnouncementBroadcastController 契約テスト（camelCase）")
class AnnouncementBroadcastControllerTest {

    private static final Long USER_ID = 1L;
    private static final Long TEAM_ID = 10L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private AnnouncementBroadcastService broadcastService;

    // フィルタ/メソッドセキュリティ コンテキストの依存解決用
    @MockitoBean
    private AuthTokenService authTokenService;
    @MockitoBean
    private UserLocaleCache userLocaleCache;
    @MockitoBean
    private ProxyInputConsentRepository proxyInputConsentRepository;
    @MockitoBean
    private ProxyInputContext proxyInputContext;
    @MockitoBean
    private AccessGuard accessGuard;

    @BeforeEach
    void setUpSecurityContext() {
        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(USER_ID.toString(), null, List.of());
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @Test
    @DisplayName("camelCase ボディで POST すると 201 となり、レスポンス JSON キーが camelCase であること")
    void acceptsCamelCaseBodyAndReturnsCamelCaseResponse() throws Exception {
        // given: サービスは結果を返す
        BroadcastResult result = BroadcastResult.builder()
                .announcementFeedId(100L)
                .channel(AnnouncementChannel.SCHEDULE)
                .contentId(500L)
                .contentUrl("/teams/10/schedules/500")
                .targetRole("MEMBERS_AND_ABOVE")
                .targetTeamIds(List.of(1L, 2L))
                .priority("NORMAL")
                .createdAt(LocalDateTime.of(2026, 6, 30, 10, 0))
                .build();
        given(broadcastService.broadcast(any(BroadcastRequest.class))).willReturn(result);

        // camelCase リクエストボディ（FE が送る形）
        String body = """
                {
                  "channel": "SCHEDULE",
                  "targetRole": "MEMBERS_AND_ABOVE",
                  "targetTeamIds": [1, 2],
                  "templateId": null,
                  "priority": "NORMAL",
                  "expiresAt": null,
                  "content": {
                    "title": "テスト告知",
                    "description": "説明",
                    "startAt": "2026-07-01T10:00:00+09:00",
                    "endAt": "2026-07-01T12:00:00+09:00",
                    "allDay": false,
                    "location": "体育館"
                  }
                }
                """;

        // when / then
        mockMvc.perform(post("/api/v1/teams/{teamId}/broadcast", TEAM_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.announcementFeedId").value(100))
                .andExpect(jsonPath("$.data.contentId").value(500))
                .andExpect(jsonPath("$.data.contentUrl").value("/teams/10/schedules/500"))
                .andExpect(jsonPath("$.data.targetRole").value("MEMBERS_AND_ABOVE"))
                .andExpect(jsonPath("$.data.targetTeamIds").isArray())
                .andExpect(jsonPath("$.data.createdAt").exists())
                // snake_case キーは存在しないこと（外れ値の再混入防止）
                .andExpect(jsonPath("$.data.announcement_feed_id").doesNotExist())
                .andExpect(jsonPath("$.data.content_id").doesNotExist())
                .andExpect(jsonPath("$.data.target_role").doesNotExist());
    }

    @Test
    @DisplayName("必須フィールド（channel）欠落で POST すると 400 COMMON_001 となること")
    void rejectsMissingRequiredFieldWith400() throws Exception {
        // given: channel を欠落させた camelCase ボディ
        String body = """
                {
                  "targetRole": "MEMBERS_AND_ABOVE",
                  "content": { "title": "タイトル" }
                }
                """;

        // when / then
        mockMvc.perform(post("/api/v1/teams/{teamId}/broadcast", TEAM_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("COMMON_001"));
    }
}
