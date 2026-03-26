package com.mannschaft.app.schedule;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.EncryptionService;
import com.mannschaft.app.common.NameResolverService;
import com.mannschaft.app.schedule.dto.GoogleCalendarStatusResponse;
import com.mannschaft.app.schedule.entity.UserGoogleCalendarConnectionEntity;
import com.mannschaft.app.schedule.repository.ScheduleRepository;
import com.mannschaft.app.schedule.repository.UserCalendarSyncSettingRepository;
import com.mannschaft.app.schedule.repository.UserGoogleCalendarConnectionRepository;
import com.mannschaft.app.schedule.repository.UserScheduleGoogleEventRepository;
import com.mannschaft.app.schedule.service.GoogleApiClient;
import com.mannschaft.app.schedule.service.GoogleCalendarService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * {@link GoogleCalendarService} の単体テスト。
 * OAuth連携・同期設定・連携解除を検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("GoogleCalendarService 単体テスト")
class GoogleCalendarServiceTest {

    @Mock
    private UserGoogleCalendarConnectionRepository connectionRepository;

    @Mock
    private UserCalendarSyncSettingRepository syncSettingRepository;

    @Mock
    private UserScheduleGoogleEventRepository googleEventRepository;

    @Mock
    private ScheduleRepository scheduleRepository;

    @Mock
    private NameResolverService nameResolverService;

    @Mock
    private EncryptionService encryptionService;

    @Mock
    private GoogleApiClient googleApiClient;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @InjectMocks
    private GoogleCalendarService googleCalendarService;

    // ========================================
    // テスト用定数・ヘルパー
    // ========================================

    private static final Long USER_ID = 100L;

    private UserGoogleCalendarConnectionEntity createActiveConnection() {
        return UserGoogleCalendarConnectionEntity.builder()
                .userId(USER_ID)
                .googleAccountEmail("test@gmail.com")
                .googleCalendarId("primary")
                .accessToken("encrypted_access")
                .refreshToken("encrypted_refresh")
                .tokenExpiresAt(LocalDateTime.now().plusHours(1))
                .isActive(true)
                .personalSyncEnabled(false)
                .build();
    }

    // ========================================
    // getConnectionStatus
    // ========================================

    @Nested
    @DisplayName("getConnectionStatus")
    class GetConnectionStatus {

        @Test
        @DisplayName("連携状態取得_連携済み_情報を返す")
        void 連携状態取得_連携済み_情報を返す() {
            // given
            UserGoogleCalendarConnectionEntity conn = createActiveConnection();
            given(connectionRepository.findByUserId(USER_ID)).willReturn(Optional.of(conn));

            // when
            GoogleCalendarStatusResponse result = googleCalendarService.getConnectionStatus(USER_ID);

            // then
            assertThat(result.isConnected()).isTrue();
            assertThat(result.getGoogleAccountEmail()).isEqualTo("test@gmail.com");
            assertThat(result.isActive()).isTrue();
        }

        @Test
        @DisplayName("連携状態取得_未連携_未連携レスポンスを返す")
        void 連携状態取得_未連携_未連携レスポンスを返す() {
            // given
            given(connectionRepository.findByUserId(USER_ID)).willReturn(Optional.empty());

            // when
            GoogleCalendarStatusResponse result = googleCalendarService.getConnectionStatus(USER_ID);

            // then
            assertThat(result.isConnected()).isFalse();
            assertThat(result.getGoogleAccountEmail()).isNull();
        }
    }

    // ========================================
    // disconnect
    // ========================================

    @Nested
    @DisplayName("disconnect")
    class Disconnect {

        @Test
        @DisplayName("連携解除_正常_トークン無効化と接続無効化される")
        void 連携解除_正常_トークン無効化と接続無効化される() {
            // given
            UserGoogleCalendarConnectionEntity conn = createActiveConnection();
            given(connectionRepository.findByUserId(USER_ID)).willReturn(Optional.of(conn));
            given(encryptionService.decrypt("encrypted_refresh")).willReturn("raw_refresh_token");

            // when
            googleCalendarService.disconnect(USER_ID);

            // then
            verify(googleApiClient).revokeToken("raw_refresh_token");
            verify(googleEventRepository).deleteAllByUserId(USER_ID);
            verify(connectionRepository).deactivate(USER_ID);
        }

        @Test
        @DisplayName("連携解除_未連携_例外スロー")
        void 連携解除_未連携_例外スロー() {
            // given
            given(connectionRepository.findByUserId(USER_ID)).willReturn(Optional.empty());

            // when & then
            assertThatThrownBy(() -> googleCalendarService.disconnect(USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(GoogleCalendarErrorCode.GOOGLE_CALENDAR_NOT_CONNECTED);
        }
    }

    // ========================================
    // toggleTeamSync
    // ========================================

    @Nested
    @DisplayName("toggleTeamSync")
    class ToggleTeamSync {

        @Test
        @DisplayName("チーム同期ON_正常_設定が更新される")
        void チーム同期ON_正常_設定が更新される() {
            // given
            UserGoogleCalendarConnectionEntity conn = createActiveConnection();
            given(connectionRepository.findByUserId(USER_ID))
                    .willReturn(Optional.of(conn));
            given(googleEventRepository.countUnsyncedSchedules(USER_ID, "TEAM", 10L)).willReturn(5);

            // when
            var result = googleCalendarService.toggleTeamSync(10L, true, USER_ID);

            // then
            assertThat(result.isEnabled()).isTrue();
            assertThat(result.getBackfillCount()).isEqualTo(5);
            verify(syncSettingRepository).upsert(USER_ID, "TEAM", 10L, true);
        }

        @Test
        @DisplayName("チーム同期_未連携_例外スロー")
        void チーム同期_未連携_例外スロー() {
            // given
            given(connectionRepository.findByUserId(USER_ID)).willReturn(Optional.empty());

            // when & then
            assertThatThrownBy(() -> googleCalendarService.toggleTeamSync(10L, true, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(GoogleCalendarErrorCode.GOOGLE_CALENDAR_NOT_CONNECTED);
        }
    }

    // ========================================
    // togglePersonalSync
    // ========================================

    @Nested
    @DisplayName("togglePersonalSync")
    class TogglePersonalSync {

        @Test
        @DisplayName("個人同期ON_正常_設定が更新される")
        void 個人同期ON_正常_設定が更新される() {
            // given
            UserGoogleCalendarConnectionEntity conn = createActiveConnection();
            given(connectionRepository.findByUserId(USER_ID))
                    .willReturn(Optional.of(conn));
            given(googleEventRepository.countUnsyncedPersonalSchedules(USER_ID)).willReturn(3);

            // when
            var result = googleCalendarService.togglePersonalSync(true, USER_ID);

            // then
            assertThat(result.isPersonalSyncEnabled()).isTrue();
            assertThat(result.getBackfillCount()).isEqualTo(3);
            verify(connectionRepository).updatePersonalSyncEnabled(USER_ID, true);
        }
    }
}
