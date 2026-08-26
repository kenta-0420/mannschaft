package com.mannschaft.app.dashboard.service;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.chat.ChannelType;
import com.mannschaft.app.chat.entity.ChatChannelEntity;
import com.mannschaft.app.chat.entity.ChatChannelMemberEntity;
import com.mannschaft.app.chat.repository.ChatChannelMemberRepository;
import com.mannschaft.app.chat.repository.ChatChannelRepository;
import com.mannschaft.app.common.storage.MediaUrlResolver;
import com.mannschaft.app.dashboard.dto.ChatHubResponse;
import com.mannschaft.app.dashboard.repository.ChatContactFolderItemRepository;
import com.mannschaft.app.dashboard.repository.ChatContactFolderRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

/**
 * {@link ChatHubService} の純ユニットテスト（Mockito）。
 *
 * <p>画像 URL 根治 Phase 2: DM パートナーの {@code avatarUrl} が DB の生 R2 キーではなく
 * {@link MediaUrlResolver} の解決済み署名付き表示 URL になることを検証する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ChatHubService 単体テスト")
class ChatHubServiceTest {

    @Mock private ChatChannelMemberRepository chatChannelMemberRepository;
    @Mock private ChatChannelRepository chatChannelRepository;
    @Mock private ChatContactFolderRepository chatContactFolderRepository;
    @Mock private ChatContactFolderItemRepository chatContactFolderItemRepository;
    @Mock private UserRepository userRepository;
    @Mock private MediaUrlResolver mediaUrlResolver;
    @InjectMocks private ChatHubService service;

    private static final Long USER_ID = 42L;
    private static final Long DM_CHANNEL_ID = 500L;
    private static final Long PARTNER_ID = 88L;

    @Test
    @DisplayName("DM経路: パートナーavatarが署名付き表示URLへ解決される")
    void getChatHub_DMパートナーavatarが解決される() {
        String avatarKey = "user/88/avatar/raw.png";
        String signedAvatar = "https://cdn.example.com/signed/avatar.png";

        ChatChannelMemberEntity ownMembership = org.mockito.Mockito.mock(ChatChannelMemberEntity.class);
        given(ownMembership.getChannelId()).willReturn(DM_CHANNEL_ID);
        given(ownMembership.getUnreadCount()).willReturn(0);
        given(chatChannelMemberRepository.findByUserId(USER_ID)).willReturn(List.of(ownMembership));

        ChatChannelEntity dmChannel = org.mockito.Mockito.mock(ChatChannelEntity.class);
        given(dmChannel.getId()).willReturn(DM_CHANNEL_ID);
        given(dmChannel.getChannelType()).willReturn(ChannelType.DM);
        given(dmChannel.getLastMessageAt()).willReturn(null);
        given(chatChannelRepository.findByMemberUserId(USER_ID)).willReturn(List.of(dmChannel));

        ChatChannelMemberEntity partnerMember = org.mockito.Mockito.mock(ChatChannelMemberEntity.class);
        given(partnerMember.getUserId()).willReturn(PARTNER_ID);
        given(chatChannelMemberRepository.findByChannelIdOrderByJoinedAtAsc(DM_CHANNEL_ID))
                .willReturn(List.of(partnerMember));

        UserEntity partner = UserEntity.builder()
                .email("partner@example.com")
                .passwordHash("hash")
                .lastName("鈴木")
                .firstName("花子")
                .displayName("suzuki")
                .avatarUrl(avatarKey)
                .build();
        ReflectionTestUtils.setField(partner, "id", PARTNER_ID);
        given(userRepository.findAllById(Set.of(PARTNER_ID))).willReturn(List.of(partner));

        given(chatContactFolderRepository.findByUserIdOrderBySortOrder(USER_ID)).willReturn(List.of());

        given(mediaUrlResolver.resolve(avatarKey)).willReturn(signedAvatar);

        ChatHubResponse response = service.getChatHub(USER_ID);

        assertThat(response.directMessages()).hasSize(1);
        assertThat(response.directMessages().get(0).partnerAvatarUrl()).isEqualTo(signedAvatar);
    }
}
