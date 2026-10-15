package com.mannschaft.app.social.event;

import com.mannschaft.app.auth.event.UserAnonymizedEvent;
import com.mannschaft.app.social.FollowerType;
import com.mannschaft.app.social.entity.UserSocialProfileEntity;
import com.mannschaft.app.social.repository.FollowRepository;
import com.mannschaft.app.social.repository.UserSocialProfileRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("SocialAnonymizationEventListener")
class SocialAnonymizationEventListenerTest {

    @Mock
    private FollowRepository followRepository;

    @Mock
    private UserSocialProfileRepository userSocialProfileRepository;

    @InjectMocks
    private SocialAnonymizationEventListener listener;

    @Nested
    @DisplayName("handleUserAnonymized")
    class HandleUserAnonymized {

        @Test
        @DisplayName("正常系: フォロー全削除とプロフィール無効化が実行される")
        void deletesFollowsAndDeactivatesProfile() {
            Long userId = 10L;
            var event = new UserAnonymizedEvent(userId, "user@example.com");
            var profile = mock(UserSocialProfileEntity.class);
            when(userSocialProfileRepository.findByUserId(userId)).thenReturn(Optional.of(profile));

            listener.handleUserAnonymized(event);

            verify(followRepository).deleteAllByUserId(userId, FollowerType.USER);
            verify(profile).deactivate();
            verify(userSocialProfileRepository).save(profile);
        }

        @Test
        @DisplayName("正常系: プロフィールが存在しない場合でも正常終了する")
        void noErrorWhenProfileNotFound() {
            Long userId = 20L;
            var event = new UserAnonymizedEvent(userId, "noone@example.com");
            when(userSocialProfileRepository.findByUserId(userId)).thenReturn(Optional.empty());

            assertDoesNotThrow(() -> listener.handleUserAnonymized(event));
            verify(followRepository).deleteAllByUserId(userId, FollowerType.USER);
        }

        @Test
        @DisplayName("例外系: Repositoryが例外を投げてもRuntimeExceptionを外に伝播させない")
        void doesNotPropagateException() {
            Long userId = 99L;
            var event = new UserAnonymizedEvent(userId, "fail@example.com");
            doThrow(new RuntimeException("DB error")).when(followRepository).deleteAllByUserId(userId, FollowerType.USER);

            assertDoesNotThrow(() -> listener.handleUserAnonymized(event));
        }
    }
}
