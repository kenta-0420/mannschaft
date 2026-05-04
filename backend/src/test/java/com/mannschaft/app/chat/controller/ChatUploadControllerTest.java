package com.mannschaft.app.chat.controller;

import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.chat.ChannelType;
import com.mannschaft.app.chat.ChatErrorCode;
import com.mannschaft.app.chat.entity.ChatChannelEntity;
import com.mannschaft.app.chat.service.ChatAttachmentService;
import com.mannschaft.app.chat.service.ChatChannelService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.common.storage.PresignedUploadResult;
import com.mannschaft.app.common.storage.StorageService;
import com.mannschaft.app.proxy.ProxyInputContext;
import com.mannschaft.app.proxy.repository.ProxyInputConsentRepository;
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

import java.time.Duration;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F04.2 チャット添付アップロードコントローラーの WebMvc テスト。
 *
 * <p>F13 Phase 4-β: presign 経路で UX ガード 500MB（413） + F13 統合クォータ（409）を検証する。</p>
 */
@WebMvcTest(ChatUploadController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("ChatUploadController WebMvc テスト")
class ChatUploadControllerTest {

    private static final Long USER_ID = 100L;
    private static final Long CHANNEL_ID = 7L;

    @Autowired private MockMvc mockMvc;

    @MockitoBean private StorageService storageService;
    @MockitoBean private ChatChannelService chatChannelService;
    @MockitoBean private ChatAttachmentService chatAttachmentService;

    // F11.3 / F14.1 共通フィルター・コンテキストの依存補完
    @MockitoBean private AuthTokenService authTokenService;
    @MockitoBean private UserLocaleCache userLocaleCache;
    @MockitoBean private ProxyInputConsentRepository proxyInputConsentRepository;
    @MockitoBean private ProxyInputContext proxyInputContext;

    private ChatChannelEntity teamChannel() {
        return ChatChannelEntity.builder()
                .channelType(ChannelType.TEAM_PUBLIC)
                .teamId(50L)
                .name("チームチャンネル")
                .createdBy(USER_ID)
                .build();
    }

    @BeforeEach
    void setUp() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(USER_ID.toString(), null, List.of()));
    }

    @Test
    @DisplayName("POST presign 正常系: クォータ OK で 200 + presigned URL を返す")
    void presign_200() throws Exception {
        given(chatChannelService.findChannelOrThrow(CHANNEL_ID)).willReturn(teamChannel());
        given(storageService.generateUploadUrl(anyString(), eq("image/jpeg"), any(Duration.class)))
                .willReturn(new PresignedUploadResult(
                        "https://r2.example/up", "chat/uuid/photo.jpg", 3600L));

        String body = """
                {"channelId": 7, "fileName":"photo.jpg", "contentType":"image/jpeg", "fileSize":1024}
                """;
        mockMvc.perform(post("/api/v1/chat/files/upload-url")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.uploadUrl").value("https://r2.example/up"));
    }

    @Test
    @DisplayName("POST presign 異常系: F13 統合クォータ超過で 409 (CHAT_019)")
    void presign_409_quota() throws Exception {
        given(chatChannelService.findChannelOrThrow(CHANNEL_ID)).willReturn(teamChannel());
        willThrow(new BusinessException(ChatErrorCode.ATTACHMENT_QUOTA_EXCEEDED))
                .given(chatAttachmentService).checkAttachmentQuota(any(), anyLong(), anyLong());

        String body = """
                {"channelId": 7, "fileName":"photo.jpg", "contentType":"image/jpeg", "fileSize":1024}
                """;
        mockMvc.perform(post("/api/v1/chat/files/upload-url")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("CHAT_019"));
    }

    @Test
    @DisplayName("POST presign 異常系: UX ガード 500MB 超過で 413 (CHAT_015)")
    void presign_413_ux_guard() throws Exception {
        given(chatChannelService.findChannelOrThrow(CHANNEL_ID)).willReturn(teamChannel());
        willThrow(new BusinessException(ChatErrorCode.ATTACHMENT_SIZE_EXCEEDED))
                .given(chatAttachmentService).checkAttachmentQuota(any(), anyLong(), anyLong());

        String body = """
                {"channelId": 7, "fileName":"big.mp4", "contentType":"video/mp4", "fileSize":600000000}
                """;
        mockMvc.perform(post("/api/v1/chat/files/upload-url")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.error.code").value("CHAT_015"));
    }

    @Test
    @DisplayName("POST presign 異常系: チャンネル未存在で 400（既定の WARN マッピング）")
    void presign_404_channel_not_found() throws Exception {
        willThrow(new BusinessException(ChatErrorCode.CHANNEL_NOT_FOUND))
                .given(chatChannelService).findChannelOrThrow(CHANNEL_ID);

        String body = """
                {"channelId": 7, "fileName":"x.jpg", "contentType":"image/jpeg", "fileSize":1}
                """;
        mockMvc.perform(post("/api/v1/chat/files/upload-url")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("CHAT_001"));
    }
}
