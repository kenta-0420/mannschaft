package com.mannschaft.app.notification;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.notification.dto.PushSubscriptionRequest;
import com.mannschaft.app.notification.entity.PushSubscriptionEntity;
import com.mannschaft.app.notification.repository.PushSubscriptionRepository;
import com.mannschaft.app.notification.service.PushSubscriptionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * {@link PushSubscriptionService} の単体テスト。
 * Web Push APIの購読登録・解除・一覧を検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PushSubscriptionService 単体テスト")
class PushSubscriptionServiceTest {

    @Mock
    private PushSubscriptionRepository pushSubscriptionRepository;

    @InjectMocks
    private PushSubscriptionService pushSubscriptionService;

    // ========================================
    // テスト用定数・ヘルパー
    // ========================================

    private static final Long USER_ID = 1L;
    private static final Long OTHER_USER_ID = 2L;
    private static final String ENDPOINT = "https://fcm.googleapis.com/fcm/send/test-endpoint";
    private static final String P256DH_KEY = "BNcRdreALRFXTkOOUHK1EtK2wtaz5Ry4YfYCA_0QTpQtUbVlUls0VJXg7A8u-Ts1XbjhazAkj7I99e8p8QfEdSk";
    private static final String AUTH_KEY = "tBHItJI5svbpC7-Nn8ziHA";
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64)";

    private PushSubscriptionRequest createRequest() {
        return new PushSubscriptionRequest(ENDPOINT, P256DH_KEY, AUTH_KEY, USER_AGENT);
    }

    private PushSubscriptionEntity createSubscriptionEntity(Long userId) {
        return PushSubscriptionEntity.builder()
                .userId(userId)
                .endpoint(ENDPOINT)
                .p256dhKey(P256DH_KEY)
                .authKey(AUTH_KEY)
                .userAgent(USER_AGENT)
                .build();
    }

    // ========================================
    // subscribe
    // ========================================

    @Nested
    @DisplayName("subscribe")
    class Subscribe {

        @Test
        @DisplayName("購読登録_正常_エンティティ返却")
        void 購読登録_正常_エンティティ返却() {
            // Given
            PushSubscriptionRequest request = createRequest();

            given(pushSubscriptionRepository.existsByEndpoint(ENDPOINT)).willReturn(false);
            given(pushSubscriptionRepository.save(any(PushSubscriptionEntity.class)))
                    .willAnswer(invocation -> invocation.getArgument(0));

            // When
            PushSubscriptionEntity result = pushSubscriptionService.subscribe(USER_ID, request);

            // Then
            assertThat(result.getUserId()).isEqualTo(USER_ID);
            assertThat(result.getEndpoint()).isEqualTo(ENDPOINT);
            assertThat(result.getP256dhKey()).isEqualTo(P256DH_KEY);
            assertThat(result.getAuthKey()).isEqualTo(AUTH_KEY);
            assertThat(result.getUserAgent()).isEqualTo(USER_AGENT);
            verify(pushSubscriptionRepository).save(any(PushSubscriptionEntity.class));
        }

        @Test
        @DisplayName("購読登録_エンドポイント重複_NOTIFICATION_005例外")
        void 購読登録_エンドポイント重複_NOTIFICATION005例外() {
            // Given
            PushSubscriptionRequest request = createRequest();

            given(pushSubscriptionRepository.existsByEndpoint(ENDPOINT)).willReturn(true);

            // When / Then
            assertThatThrownBy(() -> pushSubscriptionService.subscribe(USER_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("NOTIFICATION_005"));
        }
    }

    // ========================================
    // unsubscribe
    // ========================================

    @Nested
    @DisplayName("unsubscribe")
    class Unsubscribe {

        @Test
        @DisplayName("購読解除_正常_削除実行")
        void 購読解除_正常_削除実行() {
            // Given
            PushSubscriptionEntity entity = createSubscriptionEntity(USER_ID);

            given(pushSubscriptionRepository.findByEndpoint(ENDPOINT))
                    .willReturn(Optional.of(entity));

            // When
            pushSubscriptionService.unsubscribe(USER_ID, ENDPOINT);

            // Then
            verify(pushSubscriptionRepository).delete(entity);
        }

        @Test
        @DisplayName("購読解除_エンドポイント不在_NOTIFICATION_004例外")
        void 購読解除_エンドポイント不在_NOTIFICATION004例外() {
            // Given
            given(pushSubscriptionRepository.findByEndpoint(ENDPOINT))
                    .willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> pushSubscriptionService.unsubscribe(USER_ID, ENDPOINT))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("NOTIFICATION_004"));
        }

        @Test
        @DisplayName("購読解除_他ユーザーの購読_NOTIFICATION_004例外")
        void 購読解除_他ユーザーの購読_NOTIFICATION004例外() {
            // Given
            PushSubscriptionEntity entity = createSubscriptionEntity(OTHER_USER_ID);

            given(pushSubscriptionRepository.findByEndpoint(ENDPOINT))
                    .willReturn(Optional.of(entity));

            // When / Then
            assertThatThrownBy(() -> pushSubscriptionService.unsubscribe(USER_ID, ENDPOINT))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("NOTIFICATION_004"));
        }
    }

    // ========================================
    // listSubscriptions
    // ========================================

    @Nested
    @DisplayName("listSubscriptions")
    class ListSubscriptions {

        @Test
        @DisplayName("購読一覧取得_正常_リスト返却")
        void 購読一覧取得_正常_リスト返却() {
            // Given
            PushSubscriptionEntity entity = createSubscriptionEntity(USER_ID);

            given(pushSubscriptionRepository.findByUserId(USER_ID)).willReturn(List.of(entity));

            // When
            List<PushSubscriptionEntity> result = pushSubscriptionService.listSubscriptions(USER_ID);

            // Then
            assertThat(result).hasSize(1);
            assertThat(result.get(0).getEndpoint()).isEqualTo(ENDPOINT);
        }

        @Test
        @DisplayName("購読一覧取得_購読なし_空リスト返却")
        void 購読一覧取得_購読なし_空リスト返却() {
            // Given
            given(pushSubscriptionRepository.findByUserId(USER_ID)).willReturn(List.of());

            // When
            List<PushSubscriptionEntity> result = pushSubscriptionService.listSubscriptions(USER_ID);

            // Then
            assertThat(result).isEmpty();
        }
    }
}
