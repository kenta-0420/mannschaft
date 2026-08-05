package com.mannschaft.app.schedule;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.EncryptionService;
import com.mannschaft.app.common.NameResolverService;
import com.mannschaft.app.schedule.dto.CalendarSyncSettingsResponse;
import com.mannschaft.app.schedule.dto.CalendarSyncToggleResponse;
import com.mannschaft.app.schedule.dto.GoogleCalendarStatusResponse;
import com.mannschaft.app.schedule.dto.ManualSyncResponse;
import com.mannschaft.app.schedule.dto.PersonalSyncStatusResponse;
import com.mannschaft.app.schedule.entity.UserCalendarSyncSettingEntity;
import com.mannschaft.app.schedule.entity.UserGoogleCalendarConnectionEntity;
import com.mannschaft.app.schedule.entity.UserScheduleGoogleEventEntity;
import com.mannschaft.app.schedule.repository.ScheduleRepository;
import com.mannschaft.app.schedule.repository.UserCalendarSyncSettingRepository;
import com.mannschaft.app.schedule.repository.UserGoogleCalendarConnectionRepository;
import com.mannschaft.app.schedule.repository.UserScheduleGoogleEventRepository;
import com.mannschaft.app.schedule.service.GoogleApiClient;
import com.mannschaft.app.schedule.service.CalendarSyncAccessGuard;
import com.mannschaft.app.schedule.service.GoogleCalendarService;
import com.mannschaft.app.schedule.service.GoogleCalendarWebhookService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
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

    @Mock
    private com.mannschaft.app.schedule.service.GoogleCalendarWebhookService webhookService;

    /**
     * スコープ同期トグルの所属認可は CalendarSyncAccessGuard が担う。
     * 本テストは同期処理の振る舞いを見るため、認可は通過させた状態で検証する。
     */
    @Mock
    private CalendarSyncAccessGuard calendarSyncAccessGuard;

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
    // disconnect — Google側イベント削除（受け入れ条件 AC-1〜AC-6）
    //
    // 連携解除時に、同期済みの Google カレンダーイベントを Google 側からも削除する。
    // 現状 disconnect() は deleteEvent を一切呼ばないため、AC-1 / AC-3 / AC-4 は
    // 「deleteEvent が呼ばれる」verify で red になる（実装は出陣部隊の仕事）。
    // AC-2 / AC-5 / AC-6 は既存挙動の維持を保証するリグレッションガード。
    // ========================================

    @Nested
    @DisplayName("disconnect — Google側イベント削除")
    class DisconnectGoogleSideDeletion {

        /** Google イベントマッピングのテストダブルを生成する。 */
        private UserScheduleGoogleEventEntity mapping(String googleEventId) {
            return UserScheduleGoogleEventEntity.builder()
                    .userId(USER_ID)
                    .scheduleId(1L)
                    .googleEventId(googleEventId)
                    .lastSyncedAt(LocalDateTime.now())
                    .build();
        }

        @Test
        @DisplayName("AC-1_連携解除_全同期イベントのgoogleEventIdごとにdeleteEventが呼ばれる")
        void AC1_連携解除時_全マッピングのgoogleEventIdでdeleteEventが呼ばれる() {
            // given: スコープ横断で 3 件の同期済みイベント
            UserGoogleCalendarConnectionEntity conn = createActiveConnection();
            given(connectionRepository.findByUserId(USER_ID)).willReturn(Optional.of(conn));
            lenient().when(googleEventRepository.findByUserId(USER_ID))
                    .thenReturn(List.of(mapping("evt-personal-1"), mapping("evt-team-2"), mapping("evt-org-3")));
            lenient().when(encryptionService.decrypt(any())).thenReturn("raw_token");

            // when
            googleCalendarService.disconnect(USER_ID);

            // then: 各 googleEventId に対し connection の calendarId("primary") で deleteEvent が呼ばれる
            verify(googleApiClient).deleteEvent(any(), eq("primary"), eq("evt-personal-1"));
            verify(googleApiClient).deleteEvent(any(), eq("primary"), eq("evt-team-2"));
            verify(googleApiClient).deleteEvent(any(), eq("primary"), eq("evt-org-3"));
        }

        @Test
        @DisplayName("AC-2_連携解除_Google削除後にマッピング全削除とdeactivateが呼ばれる（既存挙動維持）")
        void AC2_連携解除時_deleteAllByUserIdとdeactivateが呼ばれる() {
            // given
            UserGoogleCalendarConnectionEntity conn = createActiveConnection();
            given(connectionRepository.findByUserId(USER_ID)).willReturn(Optional.of(conn));
            lenient().when(googleEventRepository.findByUserId(USER_ID))
                    .thenReturn(List.of(mapping("evt-1")));
            lenient().when(encryptionService.decrypt(any())).thenReturn("raw_token");

            // when
            googleCalendarService.disconnect(USER_ID);

            // then
            verify(googleEventRepository).deleteAllByUserId(USER_ID);
            verify(connectionRepository).deactivate(USER_ID);
        }

        @Test
        @DisplayName("AC-3_連携解除_個別deleteが正常返却でも残イベント削除と後続処理が継続する")
        void AC3_個別delete正常時_残りの削除と後続処理が継続する() {
            // given: 3 件。deleteEvent は 404/410 を内部で握るためモックは正常返却（no-op）
            UserGoogleCalendarConnectionEntity conn = createActiveConnection();
            given(connectionRepository.findByUserId(USER_ID)).willReturn(Optional.of(conn));
            lenient().when(googleEventRepository.findByUserId(USER_ID))
                    .thenReturn(List.of(mapping("evt-1"), mapping("evt-2"), mapping("evt-3")));
            lenient().when(encryptionService.decrypt(any())).thenReturn("raw_token");

            // when
            googleCalendarService.disconnect(USER_ID);

            // then: 全件の deleteEvent が呼ばれ、後続の全削除・無効化まで到達する
            verify(googleApiClient).deleteEvent(any(), eq("primary"), eq("evt-1"));
            verify(googleApiClient).deleteEvent(any(), eq("primary"), eq("evt-2"));
            verify(googleApiClient).deleteEvent(any(), eq("primary"), eq("evt-3"));
            verify(googleEventRepository).deleteAllByUserId(USER_ID);
            verify(connectionRepository).deactivate(USER_ID);
        }

        @Test
        @DisplayName("AC-4_連携解除_deleteEventが本番同様BusinessExceptionを投げてもdisconnectは例外にならずdeactivateまで完了する")
        void AC4_deleteEventがBusinessExceptionを投げても_disconnectは完了しdeactivateされる() {
            // given: 本番の GoogleApiClient.deleteEvent は 404/410 以外を
            //        BusinessException(GOOGLE_API_ERROR) にラップして投げる（401 を原因に包む形）。
            //        テストも本番実態と同じ例外型で検証し「テスト緑=本番も握る」を保証する。
            UserGoogleCalendarConnectionEntity conn = createActiveConnection();
            given(connectionRepository.findByUserId(USER_ID)).willReturn(Optional.of(conn));
            lenient().when(googleEventRepository.findByUserId(USER_ID))
                    .thenReturn(List.of(mapping("evt-1")));
            lenient().when(encryptionService.decrypt(any())).thenReturn("raw_token");
            lenient().doThrow(new BusinessException(GoogleCalendarErrorCode.GOOGLE_API_ERROR,
                            new HttpClientErrorException(HttpStatus.UNAUTHORIZED)))
                    .when(googleApiClient).deleteEvent(any(), any(), any());

            // when & then: disconnect 自体は例外にならない（失敗は正当理由・ベストエフォート削除を諦めて解除は通す）
            assertThatCode(() -> googleCalendarService.disconnect(USER_ID))
                    .doesNotThrowAnyException();

            // deleteEvent は実際に呼ばれ（red の要因）、失敗でも deactivate まで到達する
            verify(googleApiClient).deleteEvent(any(), eq("primary"), eq("evt-1"));
            verify(connectionRepository).deactivate(USER_ID);
        }

        @Test
        @DisplayName("AC-4b_連携解除_deleteEventが生のRestClientException(401)を投げてもdisconnectは完了しdeactivateされる")
        void AC4b_deleteEventが生のHttpClientErrorExceptionを投げても_disconnectは完了しdeactivateされる() {
            // given: 低層が BusinessException にラップし損ねて生の HttpClientErrorException
            //        （RestClientException のサブクラス）を漏らしても握れることを担保する。
            UserGoogleCalendarConnectionEntity conn = createActiveConnection();
            given(connectionRepository.findByUserId(USER_ID)).willReturn(Optional.of(conn));
            lenient().when(googleEventRepository.findByUserId(USER_ID))
                    .thenReturn(List.of(mapping("evt-1")));
            lenient().when(encryptionService.decrypt(any())).thenReturn("raw_token");
            lenient().doThrow(new HttpClientErrorException(HttpStatus.UNAUTHORIZED))
                    .when(googleApiClient).deleteEvent(any(), any(), any());

            // when & then
            assertThatCode(() -> googleCalendarService.disconnect(USER_ID))
                    .doesNotThrowAnyException();
            verify(googleApiClient).deleteEvent(any(), eq("primary"), eq("evt-1"));
            verify(connectionRepository).deactivate(USER_ID);
        }

        @Test
        @DisplayName("AC-5_連携解除_未接続ならGCAL_001で例外・Google削除は一切呼ばれない")
        void AC5_未接続_GCAL001例外でdeleteEventは呼ばれない() {
            // given
            given(connectionRepository.findByUserId(USER_ID)).willReturn(Optional.empty());

            // when & then
            assertThatThrownBy(() -> googleCalendarService.disconnect(USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(GoogleCalendarErrorCode.GOOGLE_CALENDAR_NOT_CONNECTED);
            verify(googleApiClient, never()).deleteEvent(any(), any(), any());
        }

        @Test
        @DisplayName("AC-6_連携解除_マッピング0件でも正常完了しdeleteEventは呼ばれずdeactivateされる")
        void AC6_マッピング0件_deleteEventは呼ばれず正常完了する() {
            // given: 同期済みイベントが 0 件
            UserGoogleCalendarConnectionEntity conn = createActiveConnection();
            given(connectionRepository.findByUserId(USER_ID)).willReturn(Optional.of(conn));
            lenient().when(googleEventRepository.findByUserId(USER_ID)).thenReturn(List.of());
            lenient().when(encryptionService.decrypt(any())).thenReturn("raw_token");

            // when & then
            assertThatCode(() -> googleCalendarService.disconnect(USER_ID))
                    .doesNotThrowAnyException();
            verify(googleApiClient, never()).deleteEvent(any(), any(), any());
            verify(connectionRepository).deactivate(USER_ID);
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
            // given: 所属チェックは通過させ、連携チェック（後段）で例外になることを検証する
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
            // given: 所属チェックは通過させ、連携チェック（後段）で例外になることを検証する
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

    // ========================================
    // getPersonalSync（受け入れ条件テスト）
    // ========================================

    @Nested
    @DisplayName("getPersonalSync")
    class GetPersonalSync {

        @Test
        @DisplayName("getPersonalSync_連携済みかつON_200でpersonalSyncEnabled=true,connected=true,active=true,email反映")
        void getPersonalSync_連携済みかつON_正しく返す() {
            // given
            UserGoogleCalendarConnectionEntity conn = UserGoogleCalendarConnectionEntity.builder()
                    .userId(USER_ID)
                    .googleAccountEmail("sync-on@gmail.com")
                    .googleCalendarId("primary")
                    .accessToken("enc_access")
                    .refreshToken("enc_refresh")
                    .tokenExpiresAt(LocalDateTime.now().plusHours(1))
                    .isActive(true)
                    .personalSyncEnabled(true)
                    .build();
            given(connectionRepository.findByUserId(USER_ID)).willReturn(Optional.of(conn));

            // when
            PersonalSyncStatusResponse result = googleCalendarService.getPersonalSync(USER_ID);

            // then
            assertThat(result.isConnected()).isTrue();
            assertThat(result.isActive()).isTrue();
            assertThat(result.isPersonalSyncEnabled()).isTrue();
            assertThat(result.getGoogleAccountEmail()).isEqualTo("sync-on@gmail.com");
        }

        @Test
        @DisplayName("getPersonalSync_連携済みかつOFF_200でpersonalSyncEnabled=false")
        void getPersonalSync_連携済みかつOFF_personalSyncEnabledがfalse() {
            // given
            UserGoogleCalendarConnectionEntity conn = createActiveConnection(); // personalSyncEnabled=false
            given(connectionRepository.findByUserId(USER_ID)).willReturn(Optional.of(conn));

            // when
            PersonalSyncStatusResponse result = googleCalendarService.getPersonalSync(USER_ID);

            // then
            assertThat(result.isConnected()).isTrue();
            assertThat(result.isPersonalSyncEnabled()).isFalse();
        }

        @Test
        @DisplayName("getPersonalSync_未連携_405ではなく200でconnected=false/active=false/enabled=false/email=null")
        void getPersonalSync_未連携_デフォルトfalse値を返す() {
            // given
            given(connectionRepository.findByUserId(USER_ID)).willReturn(Optional.empty());

            // when
            PersonalSyncStatusResponse result = googleCalendarService.getPersonalSync(USER_ID);

            // then
            assertThat(result.isConnected()).isFalse();
            assertThat(result.isActive()).isFalse();
            assertThat(result.isPersonalSyncEnabled()).isFalse();
            assertThat(result.getGoogleAccountEmail()).isNull();
        }

        @Test
        @DisplayName("getPersonalSync_副作用なし_personalSyncEnabledを書き換えない")
        void getPersonalSync_副作用なし_Repositoryのsave系は呼ばれない() {
            // given
            UserGoogleCalendarConnectionEntity conn = createActiveConnection();
            given(connectionRepository.findByUserId(USER_ID)).willReturn(Optional.of(conn));

            // when
            googleCalendarService.getPersonalSync(USER_ID);

            // then: save/update 系メソッドが一切呼ばれていない
            org.mockito.Mockito.verifyNoMoreInteractions(syncSettingRepository);
            org.mockito.Mockito.verify(connectionRepository).findByUserId(USER_ID);
            org.mockito.Mockito.verifyNoMoreInteractions(connectionRepository);
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
