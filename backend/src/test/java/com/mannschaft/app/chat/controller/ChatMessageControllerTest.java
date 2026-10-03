package com.mannschaft.app.chat.controller;

import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.chat.dto.MessageResponse;
import com.mannschaft.app.chat.service.ChatMessageService;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.common.security.AccessGuard;
import com.mannschaft.app.proxy.ProxyInputContext;
import com.mannschaft.app.proxy.repository.ProxyInputConsentRepository;
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

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ChatMessageController の WebMvc テスト。
 *
 * <p>主な確認事項:</p>
 * <ul>
 *   <li>POST /api/v1/chat/channels/{channelId}/messages が 201 を返す（Jackson デシリアライズ成功）</li>
 *   <li>body 欠落時は 400 を返す（@NotBlank バリデーション確認）</li>
 * </ul>
 */
@WebMvcTest(ChatMessageController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("ChatMessageController WebMvc テスト")
class ChatMessageControllerTest {

    private static final Long USER_ID = 100L;
    private static final Long CHANNEL_ID = 7L;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ChatMessageService messageService;

    // 共通フィルター・コンテキストの依存補完
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
    void setUp() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(USER_ID.toString(), null, List.of()));
    }

    @Nested
    @DisplayName("POST /api/v1/chat/channels/{channelId}/messages — メッセージ送信")
    class SendMessage {

        @Test
        @DisplayName("正常系: body のみのリクエストで 201 を返す（Jackson デシリアライズ成功の確認）")
        void 正常系_bodyのみで201() throws Exception {
            // given
            MessageResponse mockResponse = MessageResponse.builder()
                    .id(1L)
                    .senderId(USER_ID)
                    .build();
            given(messageService.sendMessage(anyLong(), any(), anyLong()))
                    .willReturn(mockResponse);

            // when / then: Jackson が SendMessageRequest をデシリアライズできれば 201 が返る
            mockMvc.perform(post("/api/v1/chat/channels/{channelId}/messages", CHANNEL_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"body":"こんにちは"}
                                    """))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.id").value(1L));
        }

        @Test
        @DisplayName("正常系: parentId 付きのリクエストで 201 を返す（スレッド返信）")
        void 正常系_parentId付きで201() throws Exception {
            // given
            MessageResponse mockResponse = MessageResponse.builder()
                    .id(2L)
                    .senderId(USER_ID)
                    .build();
            given(messageService.sendMessage(anyLong(), any(), anyLong()))
                    .willReturn(mockResponse);

            // when / then
            mockMvc.perform(post("/api/v1/chat/channels/{channelId}/messages", CHANNEL_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"body":"返信です","parentId":42}
                                    """))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.id").value(2L));
        }

        @Test
        @DisplayName("異常系: body 欠落で 400 を返す（@NotBlank バリデーション）")
        void 異常系_body欠落で400() throws Exception {
            // when / then
            mockMvc.perform(post("/api/v1/chat/channels/{channelId}/messages", CHANNEL_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"parentId":42}
                                    """))
                    .andExpect(status().isBadRequest());
        }
    }
}
