package com.mannschaft.app.schedule;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.EncryptionService;
import com.mannschaft.app.common.NameResolverService;
import com.mannschaft.app.schedule.dto.CalendarSyncSettingsResponse;
import com.mannschaft.app.schedule.dto.CalendarSyncToggleResponse;
import com.mannschaft.app.schedule.dto.GoogleCalendarStatusResponse;
import com.mannschaft.app.schedule.dto.ManualSyncResponse;
import com.mannschaft.app.schedule.entity.UserCalendarSyncSettingEntity;
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
import java.util.List;
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

        @Test
        @DisplayName("個人同期OFF_未連携_例外スロー")
        void 個人同期OFF_未連携_例外スロー() {
            // given
            given(connectionRepository.findByUserId(USER_ID)).willReturn(Optional.empty());

            // when & then
            assertThatThrownBy(() -> googleCalendarService.togglePersonalSync(false, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(GoogleCalendarErrorCode.GOOGLE_CALENDAR_NOT_CONNECTED);
        }
    }

    @Nested
    @DisplayName("getSyncSettings")
    class GetSyncSettings {

        @Test
        @DisplayName("同期設定取得_連携済みで設定あり_設定一覧を返す")
        void 同期設定取得_連携済みで設定あり_設定一覧を返す() {
            // given
            UserGoogleCalendarConnectionEntity conn = createActiveConnection();
            UserCalendarSyncSettingEntity setting = UserCalendarSyncSettingEntity.builder()
                    .userId(USER_ID).scopeType("TEAM").scopeId(10L).isEnabled(true).build();

            given(connectionRepository.findByUserId(USER_ID)).willReturn(Optional.of(conn));
            given(syncSettingRepository.findByUserId(USER_ID)).willReturn(List.of(setting));
            given(nameResolverService.resolveScopeName("TEAM", 10L)).willReturn("テストチーム");

            // when
            CalendarSyncSettingsResponse result = googleCalendarService.getSyncSettings(USER_ID);

            // then
            assertThat(result.isConnected()).isTrue();
            assertThat(result.getGoogleAccountEmail()).isEqualTo("test@gmail.com");
            assertThat(result.getSyncSettings()).hasSize(1);
            assertThat(result.getSyncSettings().get(0).scopeName()).isEqualTo("テストチーム");
            assertThat(result.getSyncSettings().get(0).isEnabled()).isTrue();
        }

        @Test
        @DisplayName("同期設定取得_未連携_設定なしを返す")
        void 同期設定取得_未連携_設定なしを返す() {
            // given
            given(connectionRepository.findByUserId(USER_ID)).willReturn(Optional.empty());
            given(syncSettingRepository.findByUserId(USER_ID)).willReturn(List.of());

            // when
            CalendarSyncSettingsResponse result = googleCalendarService.getSyncSettings(USER_ID);

            // then
            assertThat(result.isConnected()).isFalse();
            assertThat(result.getGoogleAccountEmail()).isNull();
            assertThat(result.getSyncSettings()).isEmpty();
        }
    }

    @Nested
    @DisplayName("toggleOrgSync")
    class ToggleOrgSync {

        @Test
        @DisplayName("組織同期ON_正常_設定が更新される")
        void 組織同期ON_正常_設定が更新される() {
            // given
            UserGoogleCalendarConnectionEntity conn = createActiveConnection();
            given(connectionRepository.findByUserId(USER_ID)).willReturn(Optional.of(conn));
            given(googleEventRepository.countUnsyncedSchedules(USER_ID, "ORGANIZATION", 20L)).willReturn(10);

            // when
            CalendarSyncToggleResponse result = googleCalendarService.toggleOrgSync(20L, true, USER_ID);

            // then
            assertThat(result.isEnabled()).isTrue();
            assertThat(result.getBackfillCount()).isEqualTo(10);
            verify(syncSettingRepository).upsert(USER_ID, "ORGANIZATION", 20L, true);
        }

        @Test
        @DisplayName("組織同期OFF_正常_設定が無効化される")
        void 組織同期OFF_正常_設定が無効化される() {
            // given
            UserGoogleCalendarConnectionEntity conn = createActiveConnection();
            given(connectionRepository.findByUserId(USER_ID)).willReturn(Optional.of(conn));

            // when
            CalendarSyncToggleResponse result = googleCalendarService.toggleOrgSync(20L, false, USER_ID);

            // then
            assertThat(result.isEnabled()).isFalse();
            assertThat(result.getBackfillCount()).isEqualTo(0);
        }

        @Test
        @DisplayName("組織同期_未連携_例外スロー")
        void 組織同期_未連携_例外スロー() {
            // given
            given(connectionRepository.findByUserId(USER_ID)).willReturn(Optional.empty());

            // when & then
            assertThatThrownBy(() -> googleCalendarService.toggleOrgSync(20L, true, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(GoogleCalendarErrorCode.GOOGLE_CALENDAR_NOT_CONNECTED);
        }
    }

    @Nested
    @DisplayName("manualSync")
    class ManualSync {

        @Test
        @DisplayName("手動再同期_連携済み_同期開始される")
        void 手動再同期_連携済み_同期開始される() {
            // given
            UserGoogleCalendarConnectionEntity conn = createActiveConnection();
            given(connectionRepository.findByUserId(USER_ID)).willReturn(Optional.of(conn));
            given(googleEventRepository.countAllUnsyncedSchedules(USER_ID)).willReturn(7);

            // when
            ManualSyncResponse result = googleCalendarService.manualSync(USER_ID);

            // then
            assertThat(result.getBackfillCount()).isEqualTo(7);
            assertThat(result.getMessage()).isNotBlank();
        }

        @Test
        @DisplayName("手動再同期_未連携_例外スロー")
        void 手動再同期_未連携_例外スロー() {
            // given
            given(connectionRepository.findByUserId(USER_ID)).willReturn(Optional.empty());

            // when & then
            assertThatThrownBy(() -> googleCalendarService.manualSync(USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(GoogleCalendarErrorCode.GOOGLE_CALENDAR_NOT_CONNECTED);
        }
    }

    @Nested
    @DisplayName("getConnectionStatus with error detail")
    class GetConnectionStatusWithErrorDetail {

        @Test
        @DisplayName("連携状態取得_同期エラーあり_エラー詳細が含まれる")
        void 連携状態取得_同期エラーあり_エラー詳細が含まれる() throws Exception {
            // given: lastSyncErrorType を設定したConnection
            UserGoogleCalendarConnectionEntity conn = UserGoogleCalendarConnectionEntity.builder()
                    .userId(USER_ID)
                    .googleAccountEmail("error@gmail.com")
                    .googleCalendarId("primary")
                    .accessToken("enc_access")
                    .refreshToken("enc_refresh")
                    .tokenExpiresAt(LocalDateTime.now().plusHours(1))
                    .isActive(true)
                    .personalSyncEnabled(false)
                    .lastSyncErrorType("TOKEN_EXPIRED")
                    .lastSyncErrorMessage("アクセストークンが期限切れです")
                    .lastSyncErrorAt(LocalDateTime.of(2026, 1, 1, 10, 0))
                    .build();
            given(connectionRepository.findByUserId(USER_ID)).willReturn(Optional.of(conn));

            // when
            GoogleCalendarStatusResponse result = googleCalendarService.getConnectionStatus(USER_ID);

            // then
            assertThat(result.isConnected()).isTrue();
            assertThat(result.getLastSyncError()).isNotNull();
            assertThat(result.getLastSyncError().type()).isEqualTo("TOKEN_EXPIRED");
        }
    }

    @Nested
    @DisplayName("chームSync OFF (backfillCount=0)")
    class TeamSyncOff {

        @Test
        @DisplayName("チーム同期OFF_バックフィルなし_0が返る")
        void チーム同期OFF_バックフィルなし_0が返る() {
            // given
            UserGoogleCalendarConnectionEntity conn = createActiveConnection();
            given(connectionRepository.findByUserId(USER_ID)).willReturn(Optional.of(conn));

            // when
            CalendarSyncToggleResponse result = googleCalendarService.toggleTeamSync(10L, false, USER_ID);

            // then
            assertThat(result.isEnabled()).isFalse();
            assertThat(result.getBackfillCount()).isEqualTo(0);
            verify(syncSettingRepository).upsert(USER_ID, "TEAM", 10L, false);
        }
    }
}
