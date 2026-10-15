package com.mannschaft.app.chat.controller;

import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.chat.ChannelType;
import com.mannschaft.app.chat.ChatErrorCode;
import com.mannschaft.app.chat.dto.ChannelResponse;
import com.mannschaft.app.chat.dto.MemberResponse;
import com.mannschaft.app.chat.dto.UpdateMyChannelSettingsRequest;
import com.mannschaft.app.chat.entity.ChatChannelEntity;
import com.mannschaft.app.chat.service.ChatAttachmentService;
import com.mannschaft.app.chat.service.ChatChannelService;
import com.mannschaft.app.chat.service.ChatMemberService;
import com.mannschaft.app.chat.service.ChatMessageService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.common.storage.PresignedUploadResult;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import com.mannschaft.app.common.security.AccessGuard;

/**
 * F04.2 Phase 11 第二陣 2-β: {@link ChatChannelController} の WebMvc テスト。
 *
 * <p>本テストは Phase 11 で新規追加された 2 エンドポイントに焦点を当てる:</p>
 * <ul>
 *     <li>{@code POST /chat/channels/{id}/icon/upload-url}（チャンネルアイコン Pre-signed URL）</li>
 *     <li>{@code PATCH /chat/channels/{id}/members/me}（自分のチャンネル個人設定）</li>
 * </ul>
 */
@WebMvcTest(ChatChannelController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("ChatChannelController WebMvc テスト (Phase 11 2-β)")
class ChatChannelControllerTest {

    private static final Long USER_ID = 100L;
    private static final Long CHANNEL_ID = 7L;

    @Autowired private MockMvc mockMvc;

    @MockitoBean private ChatChannelService channelService;
    @MockitoBean private ChatMemberService memberService;
    @MockitoBean private ChatMessageService messageService;
    @MockitoBean private ChatAttachmentService attachmentService;

    // F11.3 / F14.1 共通フィルター・コンテキストの依存補完
    @MockitoBean private AuthTokenService authTokenService;
    @MockitoBean private UserLocaleCache userLocaleCache;
    @MockitoBean private ProxyInputConsentRepository proxyInputConsentRepository;
    @MockitoBean private ProxyInputContext proxyInputContext;

    /** @WebMvcTest コンテキスト用: @EnableMethodSecurity 有効化後の SpEL ガード依存解決 */
    @MockitoBean
    private AccessGuard accessGuard;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(USER_ID.toString(), null, List.of()));
    }

    @Nested
    @DisplayName("POST /chat/channels/{id}/icon/upload-url")
    class GenerateIconUploadUrl {

        @Test
        @DisplayName("正常系: 200 + presigned URL + fileKey を返す")
        void 正常系() throws Exception {
            ChatChannelEntity channel = ChatChannelEntity.builder()
                    .channelType(ChannelType.TEAM_PUBLIC)
                    .teamId(50L).name("チームちゃんねる")
                    .createdBy(USER_ID).build();
            given(channelService.findChannelOrThrow(CHANNEL_ID)).willReturn(channel);
            given(attachmentService.presignChannelIconUpload(
                    any(ChatChannelEntity.class), anyLong(), eq("image/jpeg"), anyLong(), anyString()))
                    .willReturn(new PresignedUploadResult(
                            "https://r2.example/icon-url",
                            "chat/TEAM/50/icons/uuid/icon.jpg",
                            300L));

            String body = """
                    {"file_name":"icon.jpg","content_type":"image/jpeg","file_size":1024}
                    """;
            mockMvc.perform(post("/api/v1/chat/channels/{id}/icon/upload-url", CHANNEL_ID)
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.uploadUrl").value("https://r2.example/icon-url"))
                    .andExpect(jsonPath("$.data.fileKey").value("chat/TEAM/50/icons/uuid/icon.jpg"))
                    .andExpect(jsonPath("$.data.expiresInSeconds").value(300));
        }

        @Test
        @DisplayName("異常系: 権限不足は 403 (CHAT_023)")
        void 異常系_権限不足() throws Exception {
            ChatChannelEntity channel = ChatChannelEntity.builder()
                    .channelType(ChannelType.TEAM_PUBLIC)
                    .teamId(50L).name("ch").createdBy(USER_ID).build();
            given(channelService.findChannelOrThrow(CHANNEL_ID)).willReturn(channel);
            willThrow(new BusinessException(ChatErrorCode.CHANNEL_ICON_PERMISSION_DENIED))
                    .given(attachmentService).presignChannelIconUpload(
                            any(), anyLong(), anyString(), anyLong(), anyString());

            String body = """
                    {"file_name":"i.jpg","content_type":"image/jpeg","file_size":1024}
                    """;
            mockMvc.perform(post("/api/v1/chat/channels/{id}/icon/upload-url", CHANNEL_ID)
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("CHAT_023"));
        }

        @Test
        @DisplayName("異常系: サイズ 2MB 超過は 413 (CHAT_022)")
        void 異常系_サイズ超過() throws Exception {
            ChatChannelEntity channel = ChatChannelEntity.builder()
                    .channelType(ChannelType.TEAM_PUBLIC)
                    .teamId(50L).name("ch").createdBy(USER_ID).build();
            given(channelService.findChannelOrThrow(CHANNEL_ID)).willReturn(channel);
            willThrow(new BusinessException(ChatErrorCode.ICON_SIZE_EXCEEDED))
                    .given(attachmentService).presignChannelIconUpload(
                            any(), anyLong(), anyString(), anyLong(), anyString());

            String body = """
                    {"file_name":"big.png","content_type":"image/png","file_size":5242880}
                    """;
            mockMvc.perform(post("/api/v1/chat/channels/{id}/icon/upload-url", CHANNEL_ID)
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isPayloadTooLarge())
                    .andExpect(jsonPath("$.error.code").value("CHAT_022"));
        }
    }

    @Nested
    @DisplayName("GET /chat/channels/{id} — per-user 拡張の配線")
    class GetChannelDetail {

        @Test
        @DisplayName("AC-B5: memberCount / viewer / dmPartner がレスポンスに含まれる")
        void AC_B5_per_user拡張が応答に出る() throws Exception {
            ChannelResponse response = ChannelResponse.builder()
                    .id(CHANNEL_ID)
                    .identity(new ChannelResponse.ChannelIdentityDto("DM", null, null))
                    .settings(new ChannelResponse.ChannelSettingsDto(false, false, false, null))
                    .memberCount(2)
                    .viewer(new ChannelResponse.ViewerStateDto(3, true, false, "仕事", "OWNER"))
                    .dmPartner(new ChannelResponse.DmPartnerDto(200L, "田中太郎", "http://x/a.png"))
                    .build();
            given(channelService.getChannel(eq(CHANNEL_ID), eq(USER_ID))).willReturn(response);

            mockMvc.perform(get("/api/v1/chat/channels/{id}", CHANNEL_ID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.memberCount").value(2))
                    .andExpect(jsonPath("$.data.viewer.unreadCount").value(3))
                    .andExpect(jsonPath("$.data.viewer.role").value("OWNER"))
                    .andExpect(jsonPath("$.data.dmPartner.userId").value(200))
                    .andExpect(jsonPath("$.data.dmPartner.displayName").value("田中太郎"));
        }
    }

    @Nested
    @DisplayName("PATCH /chat/channels/{id}/members/me")
    class UpdateMySettings {

        @Test
        @DisplayName("正常系: 自分の個人設定を更新できる (200)")
        void 正常系() throws Exception {
            MemberResponse resp = new MemberResponse(1L, CHANNEL_ID, USER_ID, "MEMBER",
                    0, null, true, true, "プロジェクト", null);
            given(memberService.updateMySettings(eq(CHANNEL_ID), eq(USER_ID),
                    any(UpdateMyChannelSettingsRequest.class)))
                    .willReturn(resp);

            String body = """
                    {"is_muted": true, "is_pinned": true, "category": "プロジェクト"}
                    """;
            mockMvc.perform(patch("/api/v1/chat/channels/{id}/members/me", CHANNEL_ID)
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.isMuted").value(true))
                    .andExpect(jsonPath("$.data.isPinned").value(true))
                    .andExpect(jsonPath("$.data.category").value("プロジェクト"));
        }

        @Test
        @DisplayName("異常系: 非メンバーは 400 (CHAT_003 = MEMBER_NOT_FOUND, WARN デフォルト)")
        void 異常系_非メンバー() throws Exception {
            willThrow(new BusinessException(ChatErrorCode.MEMBER_NOT_FOUND))
                    .given(memberService).updateMySettings(anyLong(), anyLong(),
                            any(UpdateMyChannelSettingsRequest.class));

            String body = """
                    {"is_muted": true}
                    """;
            mockMvc.perform(patch("/api/v1/chat/channels/{id}/members/me", CHANNEL_ID)
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("CHAT_003"));
        }
    }
}
