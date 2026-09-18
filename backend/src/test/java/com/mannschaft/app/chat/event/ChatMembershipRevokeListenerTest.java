package com.mannschaft.app.chat.event;

import com.mannschaft.app.chat.repository.ChatChannelMemberRepository;
import com.mannschaft.app.role.event.MembershipChangedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** {@link ChatMembershipRevokeListener} の単体テスト。 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ChatMembershipRevokeListener 単体テスト")
class ChatMembershipRevokeListenerTest {

    private static final Long USER_ID = 100L;
    private static final Long SCOPE_ID = 50L;

    @Mock
    private ChatChannelMemberRepository chatChannelMemberRepository;

    private ChatMembershipRevokeListener listener;

    @BeforeEach
    void setUp() {
        listener = new ChatMembershipRevokeListener(chatChannelMemberRepository);
    }

    @Test
    @DisplayName("TEAM REMOVED では team scope channel だけを対象に削除する")
    void teamRemoved_チームスコープ参加行を削除する() {
        listener.onMembershipChanged(event("TEAM", MembershipChangedEvent.ChangeType.REMOVED));

        verify(chatChannelMemberRepository).deleteByUserIdAndTeamScopeChannels(USER_ID, SCOPE_ID);
        verify(chatChannelMemberRepository, never())
                .deleteByUserIdAndOrganizationScopeChannels(any(), any());
    }

    @Test
    @DisplayName("ORGANIZATION REMOVED では organization scope channel だけを対象に削除する")
    void organizationRemoved_組織スコープ参加行を削除する() {
        listener.onMembershipChanged(event("ORGANIZATION", MembershipChangedEvent.ChangeType.REMOVED));

        verify(chatChannelMemberRepository)
                .deleteByUserIdAndOrganizationScopeChannels(USER_ID, SCOPE_ID);
        verify(chatChannelMemberRepository, never())
                .deleteByUserIdAndTeamScopeChannels(any(), any());
    }

    @Test
    @DisplayName("REMOVED 以外と無関係スコープは何も削除しない")
    void removed以外と無関係スコープは何もしない() {
        listener.onMembershipChanged(event("TEAM", MembershipChangedEvent.ChangeType.ASSIGNED));
        listener.onMembershipChanged(event("VILLAGE", MembershipChangedEvent.ChangeType.REMOVED));

        verify(chatChannelMemberRepository, never()).deleteByUserIdAndTeamScopeChannels(any(), any());
        verify(chatChannelMemberRepository, never())
                .deleteByUserIdAndOrganizationScopeChannels(any(), any());
    }

    @Test
    @DisplayName("削除失敗は握り潰さず発行元トランザクションへ伝播する")
    void 削除失敗は伝播する() {
        RuntimeException failure = new RuntimeException("database failure");
        given(chatChannelMemberRepository.deleteByUserIdAndTeamScopeChannels(USER_ID, SCOPE_ID))
                .willThrow(failure);

        assertThatThrownBy(() -> listener.onMembershipChanged(
                event("TEAM", MembershipChangedEvent.ChangeType.REMOVED)))
                .isSameAs(failure);
    }

    @Test
    @DisplayName("同期リスナーは発行元トランザクションを必須にする")
    void 発行元トランザクションを必須にする() throws NoSuchMethodException {
        Transactional transactional = ChatMembershipRevokeListener.class
                .getMethod("onMembershipChanged", MembershipChangedEvent.class)
                .getAnnotation(Transactional.class);

        assertThat(transactional).isNotNull();
        assertThat(transactional.propagation()).isEqualTo(Propagation.MANDATORY);
    }

    private MembershipChangedEvent event(String scopeType, MembershipChangedEvent.ChangeType changeType) {
        return new MembershipChangedEvent(USER_ID, scopeType, SCOPE_ID, changeType);
    }
}
