package com.mannschaft.app.common.websocket;

import com.mannschaft.app.chat.entity.ChatChannelEntity;
import com.mannschaft.app.chat.repository.ChatChannelMemberRepository;
import com.mannschaft.app.chat.repository.ChatChannelRepository;
import com.mannschaft.app.chat.ws.ChatChannelSubscriptionInterceptor;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.match.live.MatchLiveSubscriptionInterceptor;
import com.mannschaft.app.match.repository.MatchRepository;
import com.mannschaft.app.match.service.MatchAccessService;
import com.mannschaft.app.match.visibility.MatchVisibilityResolver;
import com.mannschaft.app.reservation.ws.EmergencyClosureSubscriptionInterceptor;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * CMP-261104-0337 AC 0337-4 / 0337-10: 購読 Interceptor 3 種（緊急休業・match・chat）が
 * 未認証＝UNAUTHENTICATED／権限なし＝FORBIDDEN／宛先不正＝INVALID_DESTINATION を
 * 別コードの {@link StompRejectedException} で拒否し、従来の文言（message）を維持することの単体テスト。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("購読 Interceptor 3 種の拒否理由コード UT（CMP-261104-0337）")
class SubscriptionInterceptorErrorCodeTest {

    @Mock
    private MessageChannel channel;

    private static Message<byte[]> subscribe(String destination, Long sessionUserId) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setDestination(destination);
        Map<String, Object> attrs = new HashMap<>();
        if (sessionUserId != null) {
            attrs.put("userId", sessionUserId);
        }
        accessor.setSessionAttributes(attrs);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    private static void assertRejected(ThrowingCallable call, StompErrorCode expected, String expectedMessage) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(StompRejectedException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(expected);
                    assertThat(e.getMessage()).contains(expectedMessage);
                });
    }

    @Nested
    @DisplayName("緊急休業 確認状況トピック")
    class EmergencyClosure {

        @Mock
        private AccessControlService accessControlService;

        private static final String DEST = "/topic/teams/7/emergency-closures/1/confirmations";

        @Test
        @DisplayName("未認証 → UNAUTHENTICATED（文言は従来どおり）")
        void 未認証() {
            var interceptor = new EmergencyClosureSubscriptionInterceptor(accessControlService);
            assertRejected(() -> interceptor.preSend(subscribe(DEST, null), channel),
                    StompErrorCode.UNAUTHENTICATED, "臨時休業確認状況の購読権限がありません");
        }

        @Test
        @DisplayName("認証済み・ADMIN でない → FORBIDDEN")
        void 権限なし() {
            when(accessControlService.isAdminOrAbove(77L, 7L, "TEAM")).thenReturn(false);
            var interceptor = new EmergencyClosureSubscriptionInterceptor(accessControlService);
            assertRejected(() -> interceptor.preSend(subscribe(DEST, 77L), channel),
                    StompErrorCode.FORBIDDEN, "臨時休業確認状況の購読権限がありません");
        }

        @Test
        @DisplayName("teamId が数値範囲外 → INVALID_DESTINATION")
        void 宛先不正() {
            var interceptor = new EmergencyClosureSubscriptionInterceptor(accessControlService);
            String dest = "/topic/teams/99999999999999999999999/emergency-closures/1/confirmations";
            assertRejected(() -> interceptor.preSend(subscribe(dest, 77L), channel),
                    StompErrorCode.INVALID_DESTINATION, "不正");
        }
    }

    @Nested
    @DisplayName("match ライブ観戦トピック")
    class MatchLive {

        @Mock
        private MatchAccessService matchAccessService;

        private final UUID matchId = UUID.fromString("01900000-0000-7000-8000-000000000001");
        private final String dest = "/topic/matches/" + matchId + "/live";

        @Mock
        private MatchRepository matchRepository;

        @Mock
        private AccessControlService resolverAccessControl;

        @Test
        @DisplayName("0337-10: 匿名購読は実 MatchVisibilityResolver の判定（匿名は常に不可）に委譲され UNAUTHENTICATED で拒否（FORBIDDEN にならない）")
        void 匿名購読は実Resolverに委譲されUNAUTHENTICATED() {
            // 実 Resolver の挙動: 閲覧者 null は試合の実在確認より前に false（匿名で公開観戦できる試合は現状存在しない）
            MatchVisibilityResolver resolver = new MatchVisibilityResolver(matchRepository, resolverAccessControl);
            assertThat(resolver.canViewUuid(matchId, null)).isFalse();
            verifyNoInteractions(matchRepository, resolverAccessControl);

            // Interceptor は匿名を事前拒否せず canView(null, matchId) に委譲する。委譲先の結果は実 Resolver の判定に合わせる。
            when(matchAccessService.canView(null, matchId))
                    .thenAnswer(invocation -> resolver.canViewUuid(matchId, null));
            var interceptor = new MatchLiveSubscriptionInterceptor(matchAccessService);

            assertRejected(() -> interceptor.preSend(subscribe(dest, null), channel),
                    StompErrorCode.UNAUTHENTICATED, "ライブ観戦の購読権限がありません");
            verify(matchAccessService).canView(null, matchId);
        }

        @Test
        @DisplayName("未認証で閲覧不可 → UNAUTHENTICATED（ログインで解消しうる）")
        void 未認証で閲覧不可() {
            when(matchAccessService.canView(null, matchId)).thenReturn(false);
            var interceptor = new MatchLiveSubscriptionInterceptor(matchAccessService);
            assertRejected(() -> interceptor.preSend(subscribe(dest, null), channel),
                    StompErrorCode.UNAUTHENTICATED, "ライブ観戦の購読権限がありません");
        }

        @Test
        @DisplayName("認証済みで閲覧不可 → FORBIDDEN")
        void 認証済みで閲覧不可() {
            when(matchAccessService.canView(77L, matchId)).thenReturn(false);
            var interceptor = new MatchLiveSubscriptionInterceptor(matchAccessService);
            assertRejected(() -> interceptor.preSend(subscribe(dest, 77L), channel),
                    StompErrorCode.FORBIDDEN, "ライブ観戦の購読権限がありません");
        }
    }

    @Nested
    @DisplayName("chat チャネルトピック")
    class Chat {

        @Mock
        private ChatChannelRepository chatChannelRepository;

        @Mock
        private ChatChannelMemberRepository chatChannelMemberRepository;

        private static final String DEST = "/topic/channels/5";

        @Test
        @DisplayName("未認証 → UNAUTHENTICATED（文言は従来どおり）")
        void 未認証() {
            var interceptor = new ChatChannelSubscriptionInterceptor(chatChannelRepository, chatChannelMemberRepository);
            assertRejected(() -> interceptor.preSend(subscribe(DEST, null), channel),
                    StompErrorCode.UNAUTHENTICATED, "チャネルの購読権限がありません");
        }

        @Test
        @DisplayName("チャネル不在 → FORBIDDEN（存在を漏らさず非メンバーと同じコード）")
        void 不在() {
            when(chatChannelRepository.findById(5L)).thenReturn(Optional.empty());
            var interceptor = new ChatChannelSubscriptionInterceptor(chatChannelRepository, chatChannelMemberRepository);
            assertRejected(() -> interceptor.preSend(subscribe(DEST, 77L), channel),
                    StompErrorCode.FORBIDDEN, "チャネルの購読権限がありません");
        }

        @Test
        @DisplayName("非メンバー → FORBIDDEN")
        void 非メンバー() {
            ChatChannelEntity entity = mock(ChatChannelEntity.class);
            when(entity.getDeletedAt()).thenReturn(null);
            when(entity.getChannelType()).thenReturn(com.mannschaft.app.chat.ChannelType.TEAM_PRIVATE);
            when(chatChannelRepository.findById(5L)).thenReturn(Optional.of(entity));
            when(chatChannelMemberRepository.existsByChannelIdAndUserId(any(), any())).thenReturn(false);
            var interceptor = new ChatChannelSubscriptionInterceptor(chatChannelRepository, chatChannelMemberRepository);
            assertRejected(() -> interceptor.preSend(subscribe(DEST, 77L), channel),
                    StompErrorCode.FORBIDDEN, "チャネルの購読権限がありません");
        }

        @Test
        @DisplayName("channelId が数値範囲外 → INVALID_DESTINATION")
        void 宛先不正() {
            var interceptor = new ChatChannelSubscriptionInterceptor(chatChannelRepository, chatChannelMemberRepository);
            assertRejected(() -> interceptor.preSend(subscribe("/topic/channels/99999999999999999999999", 77L), channel),
                    StompErrorCode.INVALID_DESTINATION, "不正");
        }
    }
}
