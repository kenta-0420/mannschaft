package com.mannschaft.app.schedule.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.EncryptionService;
import com.mannschaft.app.common.NameResolverService;
import com.mannschaft.app.schedule.GoogleCalendarErrorCode;
import com.mannschaft.app.schedule.dto.CalendarSyncSettingsResponse;
import com.mannschaft.app.schedule.dto.CalendarSyncSettingsResponse.SyncSettingItem;
import com.mannschaft.app.schedule.dto.CalendarSyncToggleResponse;
import com.mannschaft.app.schedule.dto.GoogleCalendarConnectRequest;
import com.mannschaft.app.schedule.dto.GoogleCalendarConnectResponse;
import com.mannschaft.app.schedule.dto.GoogleCalendarStatusResponse;
import com.mannschaft.app.schedule.dto.ManualSyncResponse;
import com.mannschaft.app.schedule.dto.PersonalSyncToggleResponse;
import com.mannschaft.app.schedule.entity.ScheduleEntity;
import com.mannschaft.app.schedule.repository.ScheduleRepository;
import com.mannschaft.app.schedule.repository.UserCalendarSyncSettingRepository;
import com.mannschaft.app.schedule.repository.UserGoogleCalendarConnectionRepository;
import com.mannschaft.app.schedule.repository.UserScheduleGoogleEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Google Calendar同期サービス。OAuth連携・同期設定・スケジュール同期を担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class GoogleCalendarService {

    private static final String SCOPE_TYPE_TEAM = "TEAM";
    private static final String SCOPE_TYPE_ORGANIZATION = "ORGANIZATION";

    private final UserGoogleCalendarConnectionRepository connectionRepository;
    private final UserCalendarSyncSettingRepository syncSettingRepository;
    private final UserScheduleGoogleEventRepository googleEventRepository;
    private final ScheduleRepository scheduleRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final NameResolverService nameResolverService;
    private final EncryptionService encryptionService;

    /**
     * Google Calendar連携状態を取得する。
     *
     * @param userId ユーザーID
     * @return 連携状態レスポンス
     */
    public GoogleCalendarStatusResponse getConnectionStatus(Long userId) {
        return connectionRepository.findByUserId(userId)
                .map(conn -> new GoogleCalendarStatusResponse(
                        true,
                        conn.getGoogleAccountEmail(),
                        conn.getGoogleCalendarId(),
                        conn.getIsActive(),
                        conn.getPersonalSyncEnabled(),
                        conn.getLastSyncErrorType() != null
                                ? new GoogleCalendarStatusResponse.SyncErrorDetail(
                                conn.getLastSyncErrorType(),
                                conn.getLastSyncErrorMessage(),
                                conn.getLastSyncErrorAt())
                                : null))
                .orElse(new GoogleCalendarStatusResponse(
                        false, null, null, false, false, null));
    }

    /**
     * Google Calendar OAuth連携を実行する。認可コードからトークンを取得し、接続情報を保存する。
     *
     * @param req    連携リクエスト
     * @param userId ユーザーID
     * @return 連携レスポンス
     */
    @Transactional
    public GoogleCalendarConnectResponse connect(GoogleCalendarConnectRequest req, Long userId) {
        // TODO: stateパラメータのCSRF検証（セッションまたはRedisに保存したstateとの照合）
        log.info("Google Calendar連携開始: userId={}", userId);

        // TODO: Google OAuth token endpoint呼び出し
        // - POST https://oauth2.googleapis.com/token
        // - code, client_id, client_secret, redirect_uri, grant_type=authorization_code
        // - レスポンスから access_token, refresh_token, expires_in, id_token を取得
        String accessToken = "TODO_ACCESS_TOKEN";
        String refreshToken = "TODO_REFRESH_TOKEN";
        String googleAccountEmail = "TODO_EMAIL@gmail.com";
        String googleCalendarId = "primary";

        // AES-256-GCM で refresh_token を暗号化して保存
        String encryptedRefreshToken = encryptionService.encrypt(refreshToken);

        // 既存接続の確認（アカウント変更チェック）
        connectionRepository.findByUserId(userId).ifPresent(existing -> {
            if (!googleAccountEmail.equals(existing.getGoogleAccountEmail())) {
                // アカウント変更時: 既存のGoogleイベントマッピングを全件削除
                googleEventRepository.deleteAllByUserId(userId);
                log.info("Googleアカウント変更検出: userId={}, 旧={}, 新={}",
                        userId, existing.getGoogleAccountEmail(), googleAccountEmail);
            }
        });

        // UPSERT user_google_calendar_connections
        connectionRepository.upsert(userId, googleAccountEmail, googleCalendarId,
                encryptedRefreshToken, true);

        log.info("Google Calendar連携完了: userId={}, email={}", userId, googleAccountEmail);
        return new GoogleCalendarConnectResponse(googleAccountEmail, googleCalendarId, true);
    }

    /**
     * Google Calendar連携を解除する。トークンを無効化し、同期データを削除する。
     *
     * @param userId ユーザーID
     */
    @Transactional
    public void disconnect(Long userId) {
        connectionRepository.findByUserId(userId)
                .orElseThrow(() -> new BusinessException(GoogleCalendarErrorCode.GOOGLE_CALENDAR_NOT_CONNECTED));

        // TODO: Google Token Revoke
        // - POST https://oauth2.googleapis.com/revoke?token={refresh_token}
        log.info("Google Token Revoke実行（仮実装）: userId={}", userId);

        // Googleイベントマッピングを全件削除
        googleEventRepository.deleteAllByUserId(userId);

        // 接続を無効化
        connectionRepository.deactivate(userId);

        log.info("Google Calendar連携解除完了: userId={}", userId);
    }

    /**
     * カレンダー同期設定一覧を取得する。チーム・組織別の同期ON/OFF状態を返す。
     *
     * @param userId ユーザーID
     * @return 同期設定レスポンス
     */
    public CalendarSyncSettingsResponse getSyncSettings(Long userId) {
        var connectionOpt = connectionRepository.findByUserId(userId);

        boolean isConnected = connectionOpt.isPresent();
        String email = connectionOpt.map(c -> c.getGoogleAccountEmail()).orElse(null);
        boolean personalSync = connectionOpt.map(c -> c.getPersonalSyncEnabled()).orElse(false);

        // TODO: ユーザーの所属チーム・組織を取得し、各スコープの同期設定を構築
        // 現時点ではsyncSettingRepositoryから既存設定のみ返す
        List<SyncSettingItem> settings = syncSettingRepository.findByUserId(userId).stream()
                .map(s -> new SyncSettingItem(
                        s.getScopeType(),
                        s.getScopeId(),
                        nameResolverService.resolveScopeName(s.getScopeType(), s.getScopeId()),
                        s.getIsEnabled()))
                .toList();

        return new CalendarSyncSettingsResponse(isConnected, email, personalSync, settings);
    }

    /**
     * チームスコープのカレンダー同期をON/OFFする。
     *
     * @param teamId    チームID
     * @param isEnabled 有効化フラグ
     * @param userId    ユーザーID
     * @return 同期トグルレスポンス
     */
    @Transactional
    public CalendarSyncToggleResponse toggleTeamSync(Long teamId, boolean isEnabled, Long userId) {
        validateConnectionActive(userId);

        // UPSERT user_calendar_sync_settings
        syncSettingRepository.upsert(userId, SCOPE_TYPE_TEAM, teamId, isEnabled);

        int backfillCount = 0;
        if (isEnabled) {
            // バックフィル対象件数を算出（未同期のスケジュール数）
            backfillCount = googleEventRepository.countUnsyncedSchedules(userId, SCOPE_TYPE_TEAM, teamId);
            // 非同期でバックフィル同期を開始
            startBackfillSync(userId, SCOPE_TYPE_TEAM, teamId);
        }

        log.info("チーム同期設定変更: userId={}, teamId={}, isEnabled={}, backfillCount={}",
                userId, teamId, isEnabled, backfillCount);
        return new CalendarSyncToggleResponse(SCOPE_TYPE_TEAM, teamId, isEnabled, backfillCount);
    }

    /**
     * 組織スコープのカレンダー同期をON/OFFする。
     *
     * @param orgId     組織ID
     * @param isEnabled 有効化フラグ
     * @param userId    ユーザーID
     * @return 同期トグルレスポンス
     */
    @Transactional
    public CalendarSyncToggleResponse toggleOrgSync(Long orgId, boolean isEnabled, Long userId) {
        validateConnectionActive(userId);

        // UPSERT user_calendar_sync_settings
        syncSettingRepository.upsert(userId, SCOPE_TYPE_ORGANIZATION, orgId, isEnabled);

        int backfillCount = 0;
        if (isEnabled) {
            backfillCount = googleEventRepository.countUnsyncedSchedules(userId, SCOPE_TYPE_ORGANIZATION, orgId);
            startBackfillSync(userId, SCOPE_TYPE_ORGANIZATION, orgId);
        }

        log.info("組織同期設定変更: userId={}, orgId={}, isEnabled={}, backfillCount={}",
                userId, orgId, isEnabled, backfillCount);
        return new CalendarSyncToggleResponse(SCOPE_TYPE_ORGANIZATION, orgId, isEnabled, backfillCount);
    }

    /**
     * 個人スケジュールのカレンダー同期をON/OFFする。
     *
     * @param isEnabled 有効化フラグ
     * @param userId    ユーザーID
     * @return 個人同期トグルレスポンス
     */
    @Transactional
    public PersonalSyncToggleResponse togglePersonalSync(boolean isEnabled, Long userId) {
        validateConnectionActive(userId);

        // personalSyncEnabled更新
        connectionRepository.updatePersonalSyncEnabled(userId, isEnabled);

        int backfillCount = 0;
        if (isEnabled) {
            // 個人スケジュールの未同期件数
            backfillCount = googleEventRepository.countUnsyncedPersonalSchedules(userId);
            startPersonalBackfillSync(userId);
        }

        log.info("個人同期設定変更: userId={}, isEnabled={}, backfillCount={}",
                userId, isEnabled, backfillCount);
        return new PersonalSyncToggleResponse(isEnabled, backfillCount);
    }

    /**
     * 手動再同期を実行する。未同期スケジュールを対象に再同期を開始する。
     *
     * @param userId ユーザーID
     * @return 手動同期レスポンス
     */
    @Transactional
    public ManualSyncResponse manualSync(Long userId) {
        validateConnectionActive(userId);

        int unsyncedCount = googleEventRepository.countAllUnsyncedSchedules(userId);

        // 非同期で再同期を開始
        startFullResync(userId);

        log.info("手動再同期開始: userId={}, unsyncedCount={}", userId, unsyncedCount);
        return new ManualSyncResponse(unsyncedCount, "再同期を開始しました。完了まで数分かかる場合があります。");
    }

    /**
     * 単一スケジュールをGoogleカレンダーに同期する（内部用）。
     *
     * @param schedule スケジュールエンティティ
     * @param userId   ユーザーID
     */
    public void syncScheduleToGoogle(ScheduleEntity schedule, Long userId) {
        // TODO: Google Calendar API呼び出し
        // - POST https://www.googleapis.com/calendar/v3/calendars/{calendarId}/events
        // - または PATCH（既存イベント更新時）
        // - リクエストボディ: summary, description, start, end, location
        log.info("Google Calendar同期（仮実装）: scheduleId={}, userId={}", schedule.getId(), userId);

        // TODO: user_schedule_google_events INSERT/UPDATE
        // - schedule_id, user_id, google_event_id, sync_status, last_synced_at
    }

    // --- プライベートメソッド ---

    /**
     * Google Calendar連携がアクティブであることを検証する。
     */
    private void validateConnectionActive(Long userId) {
        connectionRepository.findByUserId(userId)
                .filter(conn -> conn.getIsActive())
                .orElseThrow(() -> new BusinessException(GoogleCalendarErrorCode.GOOGLE_CALENDAR_NOT_CONNECTED));
    }

    /**
     * スコープ指定のバックフィル同期を非同期で開始する。
     */
    @Async
    void startBackfillSync(Long userId, String scopeType, Long scopeId) {
        // TODO: 未同期スケジュールを取得し、順次Google Calendarに同期
        log.info("バックフィル同期開始（仮実装）: userId={}, scope={}:{}", userId, scopeType, scopeId);
    }

    /**
     * 個人スケジュールのバックフィル同期を非同期で開始する。
     */
    @Async
    void startPersonalBackfillSync(Long userId) {
        // TODO: 未同期の個人スケジュールを取得し、順次Google Calendarに同期
        log.info("個人バックフィル同期開始（仮実装）: userId={}", userId);
    }

    /**
     * 全スコープの再同期を非同期で開始する。
     */
    @Async
    void startFullResync(Long userId) {
        // TODO: 全同期設定を取得し、各スコープの未同期スケジュールを順次同期
        log.info("フル再同期開始（仮実装）: userId={}", userId);
    }
}
