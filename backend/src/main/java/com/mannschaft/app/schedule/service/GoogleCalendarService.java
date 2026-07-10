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
import com.mannschaft.app.schedule.dto.PersonalSyncStatusResponse;
import com.mannschaft.app.schedule.dto.PersonalSyncToggleResponse;
import com.mannschaft.app.schedule.entity.ScheduleEntity;
import com.mannschaft.app.schedule.entity.UserGoogleCalendarConnectionEntity;
import com.mannschaft.app.schedule.entity.UserScheduleGoogleEventEntity;
import com.mannschaft.app.schedule.repository.ScheduleRepository;
import com.mannschaft.app.schedule.repository.UserCalendarSyncSettingRepository;
import com.mannschaft.app.schedule.repository.UserGoogleCalendarConnectionRepository;
import com.mannschaft.app.schedule.repository.UserScheduleGoogleEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
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
    private static final String OAUTH_STATE_KEY_PREFIX = "mannschaft:google:oauth_state:";
    private static final String DEFAULT_TIMEZONE = "Asia/Tokyo";

    private final UserGoogleCalendarConnectionRepository connectionRepository;
    private final UserCalendarSyncSettingRepository syncSettingRepository;
    private final UserScheduleGoogleEventRepository googleEventRepository;
    private final ScheduleRepository scheduleRepository;
    private final NameResolverService nameResolverService;
    private final EncryptionService encryptionService;
    private final GoogleApiClient googleApiClient;
    private final StringRedisTemplate redisTemplate;
    private final GoogleCalendarWebhookService webhookService;

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
     * 個人同期状態を取得する（読み取り専用・副作用なし）。
     * 未連携でも例外を投げず、デフォルト値（connected=false 等）を返す。
     *
     * @param userId ユーザーID
     * @return 個人同期状態レスポンス
     */
    public PersonalSyncStatusResponse getPersonalSync(Long userId) {
        return connectionRepository.findByUserId(userId)
                .map(conn -> new PersonalSyncStatusResponse(
                        true,
                        conn.getIsActive(),
                        conn.getPersonalSyncEnabled(),
                        conn.getGoogleAccountEmail(),
                        conn.getLastSyncErrorType() != null
                                ? new GoogleCalendarStatusResponse.SyncErrorDetail(
                                conn.getLastSyncErrorType(),
                                conn.getLastSyncErrorMessage(),
                                conn.getLastSyncErrorAt())
                                : null))
                .orElse(new PersonalSyncStatusResponse(false, false, false, null, null));
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
        // state パラメータの CSRF 検証
        String stateKey = OAUTH_STATE_KEY_PREFIX + userId;
        String storedState = redisTemplate.opsForValue().get(stateKey);
        if (storedState == null || !storedState.equals(req.getState())) {
            throw new BusinessException(GoogleCalendarErrorCode.GOOGLE_OAUTH_FAILED);
        }
        redisTemplate.delete(stateKey);

        log.info("Google Calendar連携開始: userId={}", userId);

        // Google OAuth token endpoint 呼び出し
        GoogleApiClient.TokenResponse tokenResponse =
                googleApiClient.exchangeCode(req.getCode(), req.getRedirectUri());

        String accessToken = tokenResponse.getAccessToken();
        String refreshToken = tokenResponse.getRefreshToken();

        // ユーザー情報（メールアドレス）を取得
        GoogleApiClient.UserInfoResponse userInfo = googleApiClient.getUserInfo(accessToken);
        String googleAccountEmail = userInfo.getEmail();
        String googleCalendarId = "primary";

        // AES-256-GCM で refresh_token を暗号化して保存
        String encryptedAccessToken = encryptionService.encrypt(accessToken);
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

        // アクセストークンとトークン有効期限も保存
        connectionRepository.findByUserId(userId).ifPresent(conn -> {
            conn.updateTokens(encryptedAccessToken, encryptedRefreshToken,
                    LocalDateTime.now().plusSeconds(tokenResponse.getExpiresIn()));
            connectionRepository.save(conn);
        });

        log.info("Google Calendar連携完了: userId={}, email={}", userId, googleAccountEmail);
        return new GoogleCalendarConnectResponse(googleAccountEmail, googleCalendarId, true);
    }

    /**
     * Google OAuth 連携フロー経由の GCal 接続確立。
     * <p>
     * 通常の {@link #connect(GoogleCalendarConnectRequest, Long)} と異なり、
     * token exchange は呼び出し元（{@code AuthOAuthLinkService}）で実施済みなため、
     * access_token / refresh_token を直接受け取ってDB保存のみ行う。
     *
     * <p>TODO: auth ドメインから schedule ドメインへのクロスドメイン呼び出し。
     * 将来は GoogleCalendarLinkedEvent 等のイベント駆動で分離予定。
     *
     * @param userId       対象ユーザーID
     * @param accessToken  Google access_token（生値）
     * @param refreshToken Google refresh_token（生値、null の場合あり）
     * @param email        Google アカウントのメールアドレス
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void connectWithOAuthTokens(Long userId, String accessToken, String refreshToken, String email) {
        log.info("Google Calendar連携開始(OAuth経由): userId={}", userId);

        String googleCalendarId = "primary";

        String encryptedAccessToken = encryptionService.encrypt(accessToken);
        String encryptedRefreshToken = refreshToken != null
                ? encryptionService.encrypt(refreshToken)
                : null;

        // 既存接続の確認（アカウント変更チェック）
        connectionRepository.findByUserId(userId).ifPresent(existing -> {
            if (!email.equals(existing.getGoogleAccountEmail())) {
                googleEventRepository.deleteAllByUserId(userId);
                log.info("Googleアカウント変更検出(OAuth経由): userId={}, 旧={}, 新={}",
                        userId, existing.getGoogleAccountEmail(), email);
            }
        });

        // UPSERT user_google_calendar_connections
        connectionRepository.upsert(userId, email, googleCalendarId,
                encryptedRefreshToken != null ? encryptedRefreshToken : "", true);

        // アクセストークンと有効期限を保存（有効期限: 1時間後）
        connectionRepository.findByUserId(userId).ifPresent(conn -> {
            conn.updateTokens(encryptedAccessToken,
                    encryptedRefreshToken != null ? encryptedRefreshToken : conn.getRefreshToken(),
                    LocalDateTime.now().plusSeconds(3600));
            connectionRepository.save(conn);
        });

        log.info("Google Calendar連携完了(OAuth経由): userId={}, email={}", userId, email);
    }

    /**
     * Google Calendar連携を解除する。トークンを無効化し、同期データを削除する。
     *
     * @param userId ユーザーID
     */
    @Transactional
    public void disconnect(Long userId) {
        UserGoogleCalendarConnectionEntity connection = connectionRepository.findByUserId(userId)
                .orElseThrow(() -> new BusinessException(GoogleCalendarErrorCode.GOOGLE_CALENDAR_NOT_CONNECTED));

        // Google Token Revoke
        String refreshToken = encryptionService.decrypt(connection.getRefreshToken());
        googleApiClient.revokeToken(refreshToken);

        // Googleイベントマッピングを全件削除
        googleEventRepository.deleteAllByUserId(userId);

        // 接続を無効化
        connectionRepository.deactivate(userId);

        // AC-10: Webhook チャンネルを停止・DB から削除（ベストエフォート）
        stopWebhookChannelIfExists(userId);

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
     */
    @Transactional
    public CalendarSyncToggleResponse toggleTeamSync(Long teamId, boolean isEnabled, Long userId) {
        validateConnectionActive(userId);
        syncSettingRepository.upsert(userId, SCOPE_TYPE_TEAM, teamId, isEnabled);

        int backfillCount = 0;
        if (isEnabled) {
            backfillCount = googleEventRepository.countUnsyncedSchedules(userId, SCOPE_TYPE_TEAM, teamId);
            startBackfillSync(userId, SCOPE_TYPE_TEAM, teamId);
        }

        log.info("チーム同期設定変更: userId={}, teamId={}, isEnabled={}, backfillCount={}",
                userId, teamId, isEnabled, backfillCount);
        return new CalendarSyncToggleResponse(SCOPE_TYPE_TEAM, teamId, isEnabled, backfillCount);
    }

    /**
     * 組織スコープのカレンダー同期をON/OFFする。
     */
    @Transactional
    public CalendarSyncToggleResponse toggleOrgSync(Long orgId, boolean isEnabled, Long userId) {
        validateConnectionActive(userId);
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
     */
    @Transactional
    public PersonalSyncToggleResponse togglePersonalSync(boolean isEnabled, Long userId) {
        validateConnectionActive(userId);
        connectionRepository.updatePersonalSyncEnabled(userId, isEnabled);

        int backfillCount = 0;
        if (isEnabled) {
            backfillCount = googleEventRepository.countUnsyncedPersonalSchedules(userId);
            startPersonalBackfillSync(userId);
            // AC-14: personal_sync ON → Webhook チャンネルを登録
            registerWebhookChannelIfPersonalSync(userId);
        } else {
            // AC-14: personal_sync OFF → Webhook チャンネルを停止・削除
            stopWebhookChannelIfExists(userId);
        }

        log.info("個人同期設定変更: userId={}, isEnabled={}, backfillCount={}",
                userId, isEnabled, backfillCount);
        return new PersonalSyncToggleResponse(isEnabled, backfillCount);
    }

    /**
     * メンバーシップ終了（退会・サポータ解除）に連動して、当該ユーザー×スコープの
     * カレンダー同期設定を無効化し、Google 側のイベントを削除する（堅牢化フェーズ AC-7）。
     *
     * <p>{@code com.mannschaft.app.membership.event.MembershipEndedEvent} を購読する
     * schedule ドメインのリスナーから {@code AFTER_COMMIT} + {@code REQUIRES_NEW} で
     * 呼び出される想定（手本: {@link com.mannschaft.app.schedule.listener.ScheduleDelegationMembershipListener}）。
     * 退会後も Google カレンダーへスケジュールが push され続ける穴を塞ぐ。</p>
     *
     * <p><b>未実装（堅牢化フェーズ 試練の red 用スケルトン）</b>: 受け入れテスト（AC-7）が参照する
     * シグネチャのみ先行定義したもので、ロジックは出陣フェーズで実装する。</p>
     *
     * @param userId    退会したユーザーID
     * @param scopeType スコープ種別（"TEAM" / "ORGANIZATION"）
     * @param scopeId   スコープID（team_id または organization_id）
     */
    @Transactional
    public void handleMembershipEnded(Long userId, String scopeType, Long scopeId) {
        throw new UnsupportedOperationException(
                "未実装: GoogleCalendarService.handleMembershipEnded（Google カレンダー同期 堅牢化フェーズ 出陣で実装）");
    }

    /**
     * 手動再同期を実行する。
     */
    @Transactional
    public ManualSyncResponse manualSync(Long userId) {
        validateConnectionActive(userId);
        int unsyncedCount = googleEventRepository.countAllUnsyncedSchedules(userId);
        startFullResync(userId);

        log.info("手動再同期開始: userId={}, unsyncedCount={}", userId, unsyncedCount);
        return new ManualSyncResponse(unsyncedCount, "再同期を開始しました。完了まで数分かかる場合があります。");
    }

    /**
     * 単一スケジュールをGoogleカレンダーに同期する（内部用）。
     *
     * <p>AC-13: 同期後に Google API が返した ETag を {@link UserScheduleGoogleEventEntity} に保存する。</p>
     */
    public void syncScheduleToGoogle(ScheduleEntity schedule, Long userId) {
        // AC-12/AC-13: GOOGLE_IMPORT ソースは Google への push-back をスキップ（無限ループ防止）
        // 接続確認より先に source チェックすることで不要な DB アクセスを避ける
        if (schedule.getSource() != null
                && com.mannschaft.app.schedule.entity.ScheduleSource.GOOGLE_IMPORT.equals(schedule.getSource())) {
            log.debug("GOOGLE_IMPORT ソースのスケジュール {}: syncScheduleToGoogle をスキップ", schedule.getId());
            return;
        }

        UserGoogleCalendarConnectionEntity connection = connectionRepository.findByUserId(userId)
                .filter(UserGoogleCalendarConnectionEntity::getIsActive)
                .orElse(null);
        if (connection == null) {
            return;
        }

        String accessToken = getValidAccessToken(connection);
        String calendarId = connection.getGoogleCalendarId();

        // Calendar Event リクエスト構築
        GoogleApiClient.CalendarEventRequest eventRequest = new GoogleApiClient.CalendarEventRequest(
                schedule.getTitle(),
                schedule.getDescription(),
                schedule.getLocation(),
                GoogleApiClient.CalendarEventRequest.DateTimeValue.of(schedule.getStartAt(), DEFAULT_TIMEZONE),
                GoogleApiClient.CalendarEventRequest.DateTimeValue.of(schedule.getEndAt() != null ? schedule.getEndAt() : schedule.getStartAt(), DEFAULT_TIMEZONE)
        );

        // 既存マッピング確認（UPDATE or INSERT）
        var existingMapping = googleEventRepository
                .findByUserIdAndScheduleId(userId, schedule.getId());

        try {
            if (existingMapping.isPresent()) {
                // 既存イベントを更新（AC-13: ETag を取得して保存）
                String googleEventId = existingMapping.get().getGoogleEventId();
                String newEtag = googleApiClient.updateEvent(accessToken, calendarId, googleEventId, eventRequest);
                existingMapping.get().updateSyncedAt();
                existingMapping.get().updateGoogleEtag(newEtag);
                googleEventRepository.save(existingMapping.get());
            } else {
                // 新規イベント作成（AC-13: ETag を取得して保存）
                GoogleApiClient.CreateEventResponse createResponse =
                        googleApiClient.createEvent(accessToken, calendarId, eventRequest);
                UserScheduleGoogleEventEntity mapping = UserScheduleGoogleEventEntity.builder()
                        .userId(userId)
                        .scheduleId(schedule.getId())
                        .googleEventId(createResponse.eventId())
                        .lastSyncedAt(LocalDateTime.now())
                        .googleEtag(createResponse.etag())
                        .build();
                googleEventRepository.save(mapping);
            }

            connection.clearSyncError();
            connectionRepository.save(connection);
            log.debug("Google Calendar同期完了: scheduleId={}, userId={}", schedule.getId(), userId);
        } catch (BusinessException e) {
            connection.recordSyncError("API_ERROR", e.getMessage());
            connectionRepository.save(connection);
            log.warn("Google Calendar同期失敗: scheduleId={}, userId={}", schedule.getId(), userId, e);
        }
    }

    /**
     * personal_sync が ON の場合に Webhook チャンネルを登録する（AC-14/AC-15）。
     *
     * @param userId 対象ユーザーID
     */
    public void registerWebhookChannelIfPersonalSync(Long userId) {
        connectionRepository.findByUserId(userId)
                .filter(conn -> conn.getIsActive() && conn.getPersonalSyncEnabled())
                .ifPresent(conn -> {
                    log.info("personal_sync ON: チャンネル登録 userId={}", userId);
                    webhookService.registerWebhookChannel(userId);
                });
    }

    /**
     * Webhook チャンネルが存在する場合に停止・削除する（AC-10/AC-14）。
     *
     * @param userId 対象ユーザーID
     */
    public void stopWebhookChannelIfExists(Long userId) {
        webhookService.stopAndDeleteChannel(userId);
    }

    // --- プライベートメソッド ---

    private void validateConnectionActive(Long userId) {
        connectionRepository.findByUserId(userId)
                .filter(conn -> conn.getIsActive())
                .orElseThrow(() -> new BusinessException(GoogleCalendarErrorCode.GOOGLE_CALENDAR_NOT_CONNECTED));
    }

    /**
     * 有効なアクセストークンを取得する。期限切れの場合はリフレッシュする。
     */
    private String getValidAccessToken(UserGoogleCalendarConnectionEntity connection) {
        if (connection.getTokenExpiresAt().isAfter(LocalDateTime.now().plusMinutes(1))) {
            return encryptionService.decrypt(connection.getAccessToken());
        }

        // トークンリフレッシュ
        String refreshToken = encryptionService.decrypt(connection.getRefreshToken());
        GoogleApiClient.TokenResponse tokenResponse = googleApiClient.refreshAccessToken(refreshToken);

        String newAccessToken = tokenResponse.getAccessToken();
        String encryptedAccessToken = encryptionService.encrypt(newAccessToken);
        String encryptedRefreshToken = tokenResponse.getRefreshToken() != null
                ? encryptionService.encrypt(tokenResponse.getRefreshToken())
                : connection.getRefreshToken();

        connection.updateTokens(encryptedAccessToken, encryptedRefreshToken,
                LocalDateTime.now().plusSeconds(tokenResponse.getExpiresIn()));
        connectionRepository.save(connection);

        return newAccessToken;
    }

    /**
     * スコープ指定のバックフィル同期を非同期で開始する。
     */
    @Async("event-pool")
    void startBackfillSync(Long userId, String scopeType, Long scopeId) {
        log.info("バックフィル同期開始: userId={}, scope={}:{}", userId, scopeType, scopeId);
        List<ScheduleEntity> schedules = scheduleRepository
                .findUnsyncedByUserAndScope(userId, scopeType, scopeId);
        for (ScheduleEntity schedule : schedules) {
            syncScheduleToGoogle(schedule, userId);
        }
        log.info("バックフィル同期完了: userId={}, scope={}:{}, count={}",
                userId, scopeType, scopeId, schedules.size());
    }

    /**
     * 個人スケジュールのバックフィル同期を非同期で開始する。
     */
    @Async("event-pool")
    void startPersonalBackfillSync(Long userId) {
        log.info("個人バックフィル同期開始: userId={}", userId);
        List<ScheduleEntity> schedules = scheduleRepository
                .findUnsyncedPersonalSchedules(userId);
        for (ScheduleEntity schedule : schedules) {
            syncScheduleToGoogle(schedule, userId);
        }
        log.info("個人バックフィル同期完了: userId={}, count={}", userId, schedules.size());
    }

    /**
     * 全スコープの再同期を非同期で開始する。
     */
    @Async("event-pool")
    void startFullResync(Long userId) {
        log.info("フル再同期開始: userId={}", userId);
        // 有効な同期設定の全スコープ
        var syncSettings = syncSettingRepository.findByUserIdAndIsEnabledTrue(userId);
        for (var setting : syncSettings) {
            List<ScheduleEntity> schedules = scheduleRepository
                    .findUnsyncedByUserAndScope(userId, setting.getScopeType(), setting.getScopeId());
            for (ScheduleEntity schedule : schedules) {
                syncScheduleToGoogle(schedule, userId);
            }
        }

        // 個人同期が有効な場合
        connectionRepository.findByUserId(userId)
                .filter(conn -> conn.getPersonalSyncEnabled())
                .ifPresent(conn -> {
                    List<ScheduleEntity> personalSchedules = scheduleRepository
                            .findUnsyncedPersonalSchedules(userId);
                    for (ScheduleEntity schedule : personalSchedules) {
                        syncScheduleToGoogle(schedule, userId);
                    }
                });

        log.info("フル再同期完了: userId={}", userId);
    }
}
