package com.mannschaft.app.schedule.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.EncryptionService;
import com.mannschaft.app.schedule.EventType;
import com.mannschaft.app.schedule.GoogleCalendarErrorCode;
import com.mannschaft.app.schedule.MinViewRole;
import com.mannschaft.app.schedule.ScheduleStatus;
import com.mannschaft.app.schedule.ScheduleVisibility;
import com.mannschaft.app.schedule.entity.GoogleCalendarWebhookChannelEntity;
import com.mannschaft.app.schedule.entity.ScheduleEntity;
import com.mannschaft.app.schedule.entity.ScheduleSource;
import com.mannschaft.app.schedule.entity.SyncDirection;
import com.mannschaft.app.schedule.entity.UserGoogleCalendarConnectionEntity;
import com.mannschaft.app.schedule.entity.UserScheduleGoogleEventEntity;
import com.mannschaft.app.schedule.repository.GoogleCalendarWebhookChannelRepository;
import com.mannschaft.app.schedule.repository.ScheduleRepository;
import com.mannschaft.app.schedule.repository.UserGoogleCalendarConnectionRepository;
import com.mannschaft.app.schedule.repository.UserScheduleGoogleEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Google Calendar Phase 4 — Webhook 受信処理・チャンネル管理サービス。
 *
 * <p>担当する責務:</p>
 * <ul>
 *   <li>Webhook 通知の受信・検証・イベント取り込み（AC-1〜AC-4, AC-11）</li>
 *   <li>Webhook チャンネルの登録・停止（AC-10, AC-14, AC-15）</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GoogleCalendarWebhookService {

    private static final String RESOURCE_STATE_SYNC = "sync";
    private static final String RESOURCE_STATE_EXISTS = "exists";
    private static final String RESOURCE_STATE_UPDATED = "updated";
    private static final String EVENT_STATUS_CANCELLED = "cancelled";
    private static final String WEBHOOK_PATH = "/api/v1/webhooks/google-calendar";
    /** チャンネル期限更新トリガーの閾値（3日）。 */
    public static final long CHANNEL_RENEWAL_THRESHOLD_DAYS = 3;

    private final GoogleCalendarWebhookChannelRepository webhookChannelRepository;
    private final UserGoogleCalendarConnectionRepository connectionRepository;
    private final UserScheduleGoogleEventRepository googleEventRepository;
    private final ScheduleRepository scheduleRepository;
    private final GoogleApiClient googleApiClient;
    private final EncryptionService encryptionService;

    @Value("${app.base-url}")
    private String appBaseUrl;

    // ========================================
    // Webhook 受信
    // ========================================

    /**
     * Google Calendar から送られてくる Webhook 通知を処理する。
     *
     * <p>処理フロー:</p>
     * <ol>
     *   <li>channelId で DB 検索（なければ 404 例外）</li>
     *   <li>channel_token を定数時間比較（不一致は 403 例外）</li>
     *   <li>resourceState = "sync" / "exists" → 即 return</li>
     *   <li>"updated" → Events.list で変更一覧を取得してインポート</li>
     * </ol>
     *
     * @param channelId     X-Goog-Channel-ID ヘッダ
     * @param resourceState X-Goog-Resource-State ヘッダ
     * @param channelToken  X-Goog-Channel-Token ヘッダ
     * @param resourceId    X-Goog-Resource-ID ヘッダ
     */
    @Transactional
    public void receiveWebhookNotification(
            String channelId, String resourceState, String channelToken, String resourceId) {

        // 1. channelId で DB 検索
        GoogleCalendarWebhookChannelEntity channel = webhookChannelRepository
                .findByChannelId(channelId)
                .orElseThrow(() -> {
                    log.warn("Webhook 受信: 不明なチャンネルID channelId={}", channelId);
                    return new BusinessException(GoogleCalendarErrorCode.GOOGLE_WEBHOOK_CHANNEL_NOT_FOUND);
                });

        // 2. channel_token 定数時間比較（timing attack 防止）
        boolean tokenValid = MessageDigest.isEqual(
                channelToken.getBytes(StandardCharsets.UTF_8),
                channel.getChannelToken().getBytes(StandardCharsets.UTF_8));
        if (!tokenValid) {
            log.warn("Webhook 受信: channel_token 不一致 channelId={}", channelId);
            throw new BusinessException(GoogleCalendarErrorCode.GOOGLE_WEBHOOK_TOKEN_INVALID);
        }

        // 3. resourceState チェック
        if (RESOURCE_STATE_SYNC.equals(resourceState) || RESOURCE_STATE_EXISTS.equals(resourceState)) {
            log.debug("Webhook 受信: state={} → ノーオペレーション channelId={}", resourceState, channelId);
            return;
        }

        if (!RESOURCE_STATE_UPDATED.equals(resourceState)) {
            log.info("Webhook 受信: 未知の state={} → ログ記録のみ channelId={}", resourceState, channelId);
            return;
        }

        // 4. ユーザー情報と接続情報を取得
        Long userId = channel.getUserId();
        UserGoogleCalendarConnectionEntity connection = connectionRepository.findByUserId(userId)
                .orElse(null);
        if (connection == null || !connection.getIsActive()) {
            log.info("Webhook 受信: ユーザー接続が非アクティブ userId={}", userId);
            return;
        }
        if (!connection.getPersonalSyncEnabled()) {
            log.info("Webhook 受信: personal_sync_enabled=false userId={}", userId);
            return;
        }

        // 5. last_received_at の旧値を取得してから更新
        LocalDateTime previousLastReceived = channel.getLastReceivedAt();
        channel.updateLastReceivedAt(LocalDateTime.now());
        webhookChannelRepository.save(channel);

        // 6. updatedMin 計算（null の場合は NOW() - 30分）
        LocalDateTime updatedMin = previousLastReceived != null
                ? previousLastReceived.minusMinutes(10)
                : LocalDateTime.now().minusMinutes(30);

        // Events.list 呼び出し
        String accessToken = getValidAccessToken(connection);
        String calendarId = connection.getGoogleCalendarId();

        List<GoogleApiClient.GoogleCalendarEvent> events;
        try {
            events = googleApiClient.listUpdatedEvents(accessToken, calendarId, updatedMin);
        } catch (Exception e) {
            log.error("Webhook 受信: Events.list 失敗 userId={}", userId, e);
            connection.recordSyncError("API_ERROR", e.getMessage());
            connectionRepository.save(connection);
            return;
        }

        // 7. 各イベントを処理
        for (GoogleApiClient.GoogleCalendarEvent event : events) {
            try {
                processGoogleCalendarEvent(userId, connection, event);
            } catch (Exception e) {
                log.error("Webhook 受信: イベント処理失敗 eventId={} userId={}", event.getId(), userId, e);
                // 1件失敗しても他のイベントの処理は継続（ベストエフォート）
            }
        }

        // AC-8: チャンネル期限が 3 日以内なら非同期で更新
        if (channel.getExpiresAt().isBefore(LocalDateTime.now().plusDays(CHANNEL_RENEWAL_THRESHOLD_DAYS))) {
            log.info("Webhook 受信: チャンネル期限 3日以内 → 非同期更新 channelId={}", channelId);
            renewChannelAsync(userId);
        }
    }

    /**
     * 単一の Google Calendar イベントを Mannschaft スケジュールに反映する。
     */
    private void processGoogleCalendarEvent(
            Long userId,
            UserGoogleCalendarConnectionEntity connection,
            GoogleApiClient.GoogleCalendarEvent event) {

        // a. recurringEventId が non-null → スキップ
        if (event.getRecurringEventId() != null) {
            log.debug("繰り返しイベントをスキップ: eventId={}", event.getId());
            return;
        }

        // b. status = "cancelled" → 論理削除
        if (EVENT_STATUS_CANCELLED.equals(event.getStatus())) {
            handleCancelledEvent(event.getId());
            return;
        }

        // c/d. 新規 or 既存
        Optional<UserScheduleGoogleEventEntity> existingMapping =
                googleEventRepository.findByGoogleEventId(event.getId());

        if (existingMapping.isEmpty()) {
            // c. 新規スケジュール作成
            createScheduleFromGoogleEvent(userId, event);
        } else {
            // d. 既存: etag が変わっている場合のみ更新
            UserScheduleGoogleEventEntity mapping = existingMapping.get();
            if (event.getEtag() != null && event.getEtag().equals(mapping.getGoogleEtag())) {
                log.debug("ETag 不変のためスキップ: eventId={}", event.getId());
                return;
            }
            updateScheduleFromGoogleEvent(mapping, event);
        }
    }

    /**
     * Google から取り込んだ新規スケジュールを作成する（AC-1, AC-11）。
     */
    private void createScheduleFromGoogleEvent(Long userId, GoogleApiClient.GoogleCalendarEvent event) {
        GoogleApiClient.EventDateTime start = event.getStart();
        GoogleApiClient.EventDateTime end = event.getEnd();

        LocalDateTime startAt;
        LocalDateTime endAt;
        boolean allDay = false;

        if (start != null && start.getDateTime() != null) {
            // 通常のイベント（日時あり）
            startAt = parseRfc3339DateTime(start.getDateTime());
            endAt = end != null && end.getDateTime() != null ? parseRfc3339DateTime(end.getDateTime()) : startAt.plusHours(1);
        } else {
            // 全日予定（AC-11）
            allDay = true;
            String dateStr = start != null ? start.getDate() : null;
            if (dateStr == null) {
                log.warn("全日予定の date が null: eventId={}", event.getId());
                return;
            }
            LocalDate date = LocalDate.parse(dateStr.substring(0, 10));
            startAt = date.atStartOfDay();
            // end.date は「翌日の00:00:00」（Google Calendar の全日予定慣例）
            String endDateStr = end != null ? end.getDate() : null;
            if (endDateStr != null) {
                endAt = LocalDate.parse(endDateStr.substring(0, 10)).atStartOfDay();
            } else {
                endAt = date.plusDays(1).atStartOfDay();
            }
        }

        String title = event.getSummary();
        if (title == null) title = "(無題)";
        if (title.length() > 200) title = title.substring(0, 200);

        ScheduleEntity schedule = ScheduleEntity.builder()
                .userId(userId)
                .title(title)
                .location(event.getLocation())
                .startAt(startAt)
                .endAt(endAt)
                .allDay(allDay)
                .eventType(EventType.OTHER)
                .visibility(ScheduleVisibility.MEMBERS_ONLY)
                .minViewRole(MinViewRole.MEMBER_PLUS)
                .status(ScheduleStatus.SCHEDULED)
                .isException(false)
                .source(ScheduleSource.GOOGLE_IMPORT)
                .googleCalendarEventId(event.getId())
                .build();

        ScheduleEntity saved = scheduleRepository.save(schedule);

        // user_schedule_google_events に INSERT（sync_direction='BIDIRECTIONAL'）
        UserScheduleGoogleEventEntity mapping = UserScheduleGoogleEventEntity.builder()
                .userId(userId)
                .scheduleId(saved.getId())
                .googleEventId(event.getId())
                .lastSyncedAt(LocalDateTime.now())
                .syncDirection(SyncDirection.BIDIRECTIONAL)
                .googleEtag(event.getEtag())
                .build();
        googleEventRepository.save(mapping);

        log.info("Google イベント取り込み（新規）: eventId={} userId={}", event.getId(), userId);
    }

    /**
     * 既存スケジュールを Google イベントの更新内容で上書きする（AC-2）。
     * タイトル・start_at・end_at・location・all_day のみ更新する。
     */
    private void updateScheduleFromGoogleEvent(
            UserScheduleGoogleEventEntity mapping,
            GoogleApiClient.GoogleCalendarEvent event) {

        Optional<ScheduleEntity> scheduleOpt = scheduleRepository.findById(mapping.getScheduleId());
        if (scheduleOpt.isEmpty()) {
            log.warn("更新対象スケジュールが見つからない: scheduleId={}", mapping.getScheduleId());
            return;
        }
        ScheduleEntity schedule = scheduleOpt.get();

        GoogleApiClient.EventDateTime start = event.getStart();
        GoogleApiClient.EventDateTime end = event.getEnd();

        LocalDateTime startAt;
        LocalDateTime endAt;
        boolean allDay;

        if (start != null && start.getDateTime() != null) {
            // 時刻付き予定
            allDay = false;
            startAt = parseRfc3339DateTime(start.getDateTime());
            endAt = end != null && end.getDateTime() != null ? parseRfc3339DateTime(end.getDateTime()) : startAt.plusHours(1);
        } else {
            // 全日予定（AC-11）
            allDay = true;
            String dateStr = start != null ? start.getDate() : null;
            if (dateStr == null) return;
            LocalDate date = LocalDate.parse(dateStr.substring(0, 10));
            startAt = date.atStartOfDay();
            String endDateStr = end != null ? end.getDate() : null;
            endAt = endDateStr != null
                    ? LocalDate.parse(endDateStr.substring(0, 10)).atStartOfDay()
                    : date.plusDays(1).atStartOfDay();
        }

        String title = event.getSummary();
        if (title == null) title = "(無題)";
        if (title.length() > 200) title = title.substring(0, 200);

        // タイトル/日時/場所のみ更新（競合解決ルール: 次回 Mannschaft での更新時に Google の値が上書き）
        schedule.updateScheduleFields(title, schedule.getDescription(), event.getLocation(), startAt, endAt, schedule.getColor());
        // Google 側で「時刻付き ↔ 全日」変更があった場合に all_day を反映（バグ修正: AC-11）
        schedule.updateAllDay(allDay);
        scheduleRepository.save(schedule);

        // google_etag を最新値に更新
        mapping.updateGoogleEtag(event.getEtag());
        mapping.updateSyncedAt();
        googleEventRepository.save(mapping);

        log.info("Google イベント取り込み（更新）: eventId={} scheduleId={}", event.getId(), mapping.getScheduleId());
    }

    /**
     * status=cancelled の Google イベントに対応するスケジュールを論理削除する（AC-3）。
     */
    private void handleCancelledEvent(String googleEventId) {
        Optional<UserScheduleGoogleEventEntity> mappingOpt =
                googleEventRepository.findByGoogleEventId(googleEventId);
        if (mappingOpt.isEmpty()) {
            log.debug("キャンセルされた Google イベントに対応するマッピングが未存在: eventId={}", googleEventId);
            return;
        }
        UserScheduleGoogleEventEntity mapping = mappingOpt.get();
        scheduleRepository.findById(mapping.getScheduleId()).ifPresent(schedule -> {
            schedule.softDelete();
            scheduleRepository.save(schedule);
            log.info("Google キャンセルイベントによるスケジュール論理削除: scheduleId={}", mapping.getScheduleId());
        });
        googleEventRepository.delete(mapping);
    }

    // ========================================
    // チャンネル管理
    // ========================================

    /**
     * Google Calendar Watch API で Webhook チャンネルを登録し、DB に UPSERT する。
     *
     * <p>旧チャンネルが存在する場合は、新チャンネル登録後に旧チャンネルをベストエフォートで停止する。</p>
     *
     * @param userId 対象ユーザーID
     */
    @Transactional
    public void registerWebhookChannel(Long userId) {
        UserGoogleCalendarConnectionEntity connection = connectionRepository.findByUserId(userId)
                .filter(UserGoogleCalendarConnectionEntity::getIsActive)
                .orElse(null);
        if (connection == null) {
            log.info("チャンネル登録スキップ: 接続が非アクティブ userId={}", userId);
            return;
        }

        String accessToken = getValidAccessToken(connection);
        String calendarId = connection.getGoogleCalendarId();

        // 新チャンネルの ID・トークン生成
        String newChannelId = UUID.randomUUID().toString();
        String newToken = generateChannelToken();
        String webhookUrl = appBaseUrl + WEBHOOK_PATH;

        // 旧チャンネル情報を退避
        Optional<GoogleCalendarWebhookChannelEntity> existingChannelOpt =
                webhookChannelRepository.findByUserId(userId);

        // Google Watch API 呼び出し
        GoogleApiClient.WatchChannelResponse watchResponse;
        try {
            watchResponse = googleApiClient.watchCalendar(accessToken, calendarId, newChannelId, newToken, webhookUrl);
        } catch (Exception e) {
            log.error("チャンネル登録失敗: userId={}", userId, e);
            throw new BusinessException(GoogleCalendarErrorCode.GOOGLE_API_ERROR, e);
        }

        // DB に UPSERT
        if (existingChannelOpt.isPresent()) {
            GoogleCalendarWebhookChannelEntity existing = existingChannelOpt.get();
            String oldChannelId = existing.getChannelId();
            String oldResourceId = existing.getResourceId();

            existing.updateChannel(
                    watchResponse.channelId(),
                    watchResponse.resourceId(),
                    newToken,
                    watchResponse.expiresAt());
            webhookChannelRepository.save(existing);

            // 旧チャンネルをベストエフォートで停止
            stopChannelBestEffort(accessToken, oldChannelId, oldResourceId);
        } else {
            GoogleCalendarWebhookChannelEntity newChannel = GoogleCalendarWebhookChannelEntity.builder()
                    .userId(userId)
                    .channelId(watchResponse.channelId())
                    .resourceId(watchResponse.resourceId())
                    .channelToken(newToken)
                    .expiresAt(watchResponse.expiresAt())
                    .build();
            webhookChannelRepository.save(newChannel);
        }

        log.info("チャンネル登録完了: userId={}, channelId={}", userId, watchResponse.channelId());
    }

    /**
     * Google Calendar Channels.stop でチャンネルを停止し、DB から削除する。
     * ベストエフォート（Google API 失敗しても DB からは削除する）。
     *
     * @param userId 対象ユーザーID
     */
    @Transactional
    public void stopAndDeleteChannel(Long userId) {
        Optional<GoogleCalendarWebhookChannelEntity> channelOpt =
                webhookChannelRepository.findByUserId(userId);
        if (channelOpt.isEmpty()) {
            log.debug("停止対象チャンネルが存在しない userId={}", userId);
            return;
        }
        GoogleCalendarWebhookChannelEntity channel = channelOpt.get();

        // アクセストークン取得（ベストエフォートのため取得失敗時も続行）
        try {
            UserGoogleCalendarConnectionEntity connection = connectionRepository.findByUserId(userId).orElse(null);
            if (connection != null) {
                String accessToken = getValidAccessToken(connection);
                stopChannelBestEffort(accessToken, channel.getChannelId(), channel.getResourceId());
            }
        } catch (Exception e) {
            log.warn("チャンネル停止のアクセストークン取得失敗（DB削除は続行）: userId={}", userId, e);
        }

        webhookChannelRepository.delete(channel);
        log.info("チャンネル停止・DB削除完了: userId={}", userId);
    }

    /**
     * 期限間近のチャンネルを再登録する（日次バッチから呼ばれる）。
     *
     * @param channel 更新対象チャンネルエンティティ
     */
    public void renewChannel(GoogleCalendarWebhookChannelEntity channel) {
        registerWebhookChannel(channel.getUserId());
    }

    // ========================================
    // 内部ヘルパー
    // ========================================

    /**
     * チャンネル更新を非同期で実行する（Webhook 受信時の期限チェックから呼ばれる）。
     */
    @Async("event-pool")
    void renewChannelAsync(Long userId) {
        try {
            registerWebhookChannel(userId);
        } catch (Exception e) {
            log.error("チャンネル非同期更新失敗: userId={}", userId, e);
        }
    }

    private void stopChannelBestEffort(String accessToken, String channelId, String resourceId) {
        try {
            googleApiClient.stopChannel(accessToken, channelId, resourceId);
        } catch (Exception e) {
            log.warn("チャンネル停止失敗（ベストエフォート・無視）: channelId={}", channelId, e);
        }
    }

    /**
     * SecureRandom で 32 バイトの channel_token（hex 64 文字）を生成する。
     */
    private String generateChannelToken() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    /**
     * 有効なアクセストークンを取得する（期限切れの場合はリフレッシュ）。
     */
    private String getValidAccessToken(UserGoogleCalendarConnectionEntity connection) {
        if (connection.getTokenExpiresAt().isAfter(LocalDateTime.now().plusMinutes(1))) {
            return encryptionService.decrypt(connection.getAccessToken());
        }
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
     * RFC3339 形式の日時文字列を LocalDateTime に変換する。
     * 例: "2026-08-01T10:00:00+09:00" → LocalDateTime（タイムゾーンはシステム既定に変換）
     */
    private LocalDateTime parseRfc3339DateTime(String rfc3339) {
        try {
            java.time.OffsetDateTime odt = java.time.OffsetDateTime.parse(rfc3339);
            return odt.withOffsetSameInstant(java.time.ZoneOffset.ofHours(9)).toLocalDateTime();
        } catch (Exception e) {
            log.warn("RFC3339 日時のパース失敗: {} → フォールバック", rfc3339);
            return LocalDateTime.parse(rfc3339.substring(0, 19));
        }
    }
}
