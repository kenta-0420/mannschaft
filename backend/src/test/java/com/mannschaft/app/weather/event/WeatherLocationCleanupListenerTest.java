package com.mannschaft.app.weather.event;

import com.mannschaft.app.auth.event.UserAnonymizedEvent;
import com.mannschaft.app.weather.repository.UserWeatherLocationRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link WeatherLocationCleanupListener} の単体テスト。
 */
@DisplayName("WeatherLocationCleanupListener 単体テスト")
@ExtendWith(MockitoExtension.class)
class WeatherLocationCleanupListenerTest {

    @Mock private UserWeatherLocationRepository userWeatherLocationRepository;

    @InjectMocks private WeatherLocationCleanupListener listener;

    @Test
    @DisplayName("UserAnonymizedEvent を受信したら deleteByUserId を呼ぶ")
    void handleUserAnonymized_callsDeleteByUserId() {
        Long userId = 42L;
        when(userWeatherLocationRepository.deleteByUserId(userId)).thenReturn(1);

        listener.handleUserAnonymized(new UserAnonymizedEvent(userId, "test@example.com"));

        verify(userWeatherLocationRepository).deleteByUserId(userId);
    }

    @Test
    @DisplayName("Repository が例外を投げてもイベント処理は飲み込む（WARN ログのみ）")
    void handleUserAnonymized_swallowsRepositoryException() {
        Long userId = 43L;
        when(userWeatherLocationRepository.deleteByUserId(userId))
                .thenThrow(new RuntimeException("DB connection lost"));

        // 例外が外に伝播しないこと（テストが Pass = 例外が握りつぶされている）
        listener.handleUserAnonymized(new UserAnonymizedEvent(userId, "test@example.com"));

        verify(userWeatherLocationRepository).deleteByUserId(userId);
    }
}
