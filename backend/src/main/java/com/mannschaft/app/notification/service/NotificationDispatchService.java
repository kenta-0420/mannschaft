package com.mannschaft.app.notification.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.common.visibility.ContentVisibilityChecker;
import com.mannschaft.app.common.visibility.NotificationSourceTypeMapper;
import com.mannschaft.app.common.visibility.ReferenceType;
import com.mannschaft.app.notification.NotificationMapper;
import com.mannschaft.app.notification.NotificationType;
import com.mannschaft.app.notification.dto.NotificationResponse;
import com.mannschaft.app.notification.entity.NotificationEntity;
import com.mannschaft.app.notification.entity.NotificationPreferenceEntity;
import com.mannschaft.app.notification.entity.NotificationTypePreferenceEntity;
import com.mannschaft.app.notification.entity.PushSubscriptionEntity;
import com.mannschaft.app.notification.repository.NotificationPreferenceRepository;
import com.mannschaft.app.notification.repository.NotificationTypePreferenceRepository;
import com.mannschaft.app.notification.repository.PushSubscriptionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 通知配信サービス。通知の実際の送信処理（WebSocket・PWA Push等）を担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationDispatchService {

    private final PushSubscriptionService pushSubscriptionService;
    private final NotificationPreferenceService preferenceService;
    private final SimpMessagingTemplate messagingTemplate;
    private final NotificationMapper notificationMapper;
    private final WebPushService webPushService;
    private final ObjectMapper objectMapper;

    /**
     * fan-out 抜本改修 P1: チャンク配信（{@link #dispatchBatch}）の設定/種別/購読を
     * <b>チャンク単位で一括先読み</b>するためのリポジトリ群。受信者ごと 3 クエリの N+1 を
     * O(チャンク数) に畳む（AC-10）。単発配信（{@link #dispatch}）は従来どおり
     * {@link NotificationPreferenceService} / {@link PushSubscriptionService} を用いる。
     */
    private final NotificationPreferenceRepository preferenceRepository;
    private final NotificationTypePreferenceRepository typePreferenceRepository;
    private final PushSubscriptionRepository pushSubscriptionRepository;

    /**
     * F00 Phase F セキュリティ漏れ修正で導入。配信直前の二重防御として
     * 受信者がソースコンテンツを閲覧可能かを再確認する。
     * {@link NotificationService} 側で既にガード済だが、外部から
     * 直接 {@link #dispatch} を呼ぶ経路 (DB から復元した古い通知の再送等) でも
     * 漏れなくガードするための fail-safe。
     *
     * <p>設計書: {@code docs/features/F00_content_visibility_resolver.md} §11.1 / §13.5。
     */
    private final ContentVisibilityChecker visibilityChecker;

    /**
     * 通知を配信する。ユーザーの設定を確認し、有効なチャネルに送信する。
     * 確認通知（CONFIRMABLE_NOTIFICATION*）は opt-out 設定を無視して強制配信する。
     *
     * <p>F00 Phase F: 配信直前にも visibility ガードを行い、Resolver 配備済の
     * sourceType に対しては受信者の閲覧可否を確認してから送信する。
     *
     * <p><b>Issue #2953: プールは {@code notification-dispatch-pool} に固定する（executor 無指定にしない）。</b>
     * 無指定だと {@code @Primary} の {@code event-pool} に載り、{@code AFTER_COMMIT + @Async("event-pool")} の
     * 配送リスナー →
     * {@link NotificationDeliveryRunner#sendOne}（{@code REQUIRES_NEW}）→ 本メソッド、という経路で
     * <b>呼び出し元と同じプールへの自己投入</b>になる。飽和すると既定 AbortPolicy の
     * {@code RejectedExecutionException} が {@code sendOne} の {@code REQUIRES_NEW} を巻き戻し、
     * 作成済みの通知行ごと消える。専用プール（CallerRuns）に固定することで、
     * 自己投入と拒否によるロールバックの双方を構造的に断つ。</p>
     */
    @Async("notification-dispatch-pool")
    public void dispatch(NotificationEntity notification) {
        if (notification == null) {
            return;
        }
        Long userId = notification.getUserId();

        // ----------------------------------------------------------------
        // F00 Phase F: 配信前 visibility ガード (二重防御 §11.1)
        // ----------------------------------------------------------------
        if (!isAccessibleForRecipient(notification)) {
            log.warn("通知配信スキップ (visibility deny): userId={}, type={}, sourceType={}, sourceId={}",
                    userId, notification.getNotificationType(),
                    notification.getSourceType(), notification.getSourceId());
            return;
        }

        // 確認通知（CONFIRMABLE_NOTIFICATION*）は強制配信のため opt-out チェックをスキップ
        if (isConfirmableNotification(notification.getNotificationType())) {
            log.debug("確認通知は強制配信（opt-out スキップ）: userId={}, type={}",
                    userId, notification.getNotificationType());
            sendViaWebSocket(notification);
            sendViaPush(notification);
            return;
        }

        // スコープ別の通知設定を確認
        boolean scopeEnabled = preferenceService.isNotificationEnabled(
                userId, notification.getScopeType().name(), notification.getScopeId());
        if (!scopeEnabled) {
            log.debug("通知スキップ(スコープ無効): userId={}, scopeType={}, scopeId={}",
                    userId, notification.getScopeType(), notification.getScopeId());
            return;
        }

        // 通知種別の設定を確認
        boolean typeEnabled = preferenceService.isTypeEnabled(userId, notification.getNotificationType());
        if (!typeEnabled) {
            log.debug("通知スキップ(種別無効): userId={}, type={}", userId, notification.getNotificationType());
            return;
        }

        // WebSocket送信
        sendViaWebSocket(notification);

        // PWA Push送信
        sendViaPush(notification);
    }

    /**
     * 受信者チャンクをまとめて配信する（fan-out 抜本改修 P1・専用プール {@code notification-fanout-pool}）。
     *
     * <p>{@link #dispatch} が通知 1 件ごとに設定/種別/購読の 3 クエリを引く（受信者数に線形な N+1）のに対し、
     * 本メソッドは<b>チャンク単位で 3 クエリを一括先読み</b>して {@code Map} 化し、各受信者の配信可否を
     * メモリ参照で判定する（AC-10: 発行クエリ数を O(チャンク数) に）。判定ロジック（visibility 二重防御・
     * 確認通知の強制配信・スコープ/種別の opt-out）は {@link #dispatch} と同一に保つ。</p>
     *
     * <p>チャンクは fan-out 由来のため {@code scopeType}/{@code scopeId}/{@code notificationType} が一様である
     * 前提で先読みキーを組む（{@code notifyAllPreAuthorized} が同一パラメータで生成する）。万一混在しても、
     * 先読みに載らない設定は「行なし＝既定 ON / enum 既定値」として補完され、単発 {@link #dispatch} と
     * 同じ既定に収束する。</p>
     *
     * <p>本メソッドは専用プール {@code notification-fanout-pool} 上で非同期実行される。
     * バルク INSERT で JPA を迂回した通知は id を持たない（P1 では生成キー未使用）ため、
     * WebSocket/Push ペイロードは id 以外の内容で構成される（一覧 REST は DB の id 付きで取得される）。</p>
     *
     * @param chunk 同一 fan-out で生成された受信者チャンク（各 {@link NotificationEntity} は user_id 以外が一様）
     */
    @Async("notification-fanout-pool")
    public void dispatchBatch(List<NotificationEntity> chunk) {
        if (chunk == null || chunk.isEmpty()) {
            return;
        }
        List<Long> userIds = chunk.stream()
                .map(NotificationEntity::getUserId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (userIds.isEmpty()) {
            return;
        }

        NotificationEntity sample = chunk.get(0);
        String scopeType = sample.getScopeType().name();
        Long scopeId = sample.getScopeId();
        String type = sample.getNotificationType();

        // --- チャンク一括先読み（受信者ごと 3 クエリ N+1 → 3 クエリ／チャンク）---
        Map<Long, Boolean> scopeEnabledMap = new HashMap<>();
        for (NotificationPreferenceEntity p : preferenceRepository.findForDispatchBatch(userIds, scopeType, scopeId)) {
            scopeEnabledMap.put(p.getUserId(), Boolean.TRUE.equals(p.getIsEnabled()));
        }
        Map<Long, Boolean> typeEnabledMap = new HashMap<>();
        for (NotificationTypePreferenceEntity t : typePreferenceRepository.findByUserIdInAndNotificationType(userIds, type)) {
            typeEnabledMap.put(t.getUserId(), Boolean.TRUE.equals(t.getIsEnabled()));
        }
        Map<Long, List<PushSubscriptionEntity>> subsMap = pushSubscriptionRepository.findByUserIdIn(userIds).stream()
                .collect(Collectors.groupingBy(PushSubscriptionEntity::getUserId));

        // 行が無い受信者の種別既定値（DAILY_DIGEST のみ false 等）。isTypeEnabled と同一の既定。
        boolean typeDefaultEnabled = NotificationType.fromValue(type)
                .map(NotificationType::isDefaultEnabled)
                .orElse(true);

        int delivered = 0;
        for (NotificationEntity n : chunk) {
            try {
                if (dispatchFromPrefetched(n, scopeEnabledMap, typeEnabledMap, subsMap, typeDefaultEnabled)) {
                    delivered++;
                }
            } catch (Exception e) {
                log.warn("チャンク配信失敗（継続）: userId={}, type={}, error={}",
                        n.getUserId(), n.getNotificationType(), e.getMessage());
            }
        }
        log.debug("チャンク配信完了: type={}, scopeType={}, chunkSize={}, delivered={}",
                type, scopeType, chunk.size(), delivered);
    }

    /**
     * 先読み済みの設定/種別/購読 Map を用いて 1 件を配信する（{@link #dispatchBatch} のループ本体）。
     * 判定順序・分岐は {@link #dispatch} と同一（visibility → 確認通知強制 → スコープ → 種別）。
     *
     * @return WebSocket/Push いずれかを送出したら true（opt-out/visibility deny で抑制したら false）
     */
    private boolean dispatchFromPrefetched(NotificationEntity notification,
                                           Map<Long, Boolean> scopeEnabledMap,
                                           Map<Long, Boolean> typeEnabledMap,
                                           Map<Long, List<PushSubscriptionEntity>> subsMap,
                                           boolean typeDefaultEnabled) {
        Long userId = notification.getUserId();

        // F00 Phase F: 配信前 visibility ガード（二重防御・単発 dispatch と同一）
        if (!isAccessibleForRecipient(notification)) {
            log.warn("通知配信スキップ (visibility deny): userId={}, type={}, sourceType={}, sourceId={}",
                    userId, notification.getNotificationType(),
                    notification.getSourceType(), notification.getSourceId());
            return false;
        }

        List<PushSubscriptionEntity> subscriptions = subsMap.getOrDefault(userId, List.of());

        // 確認通知は opt-out 無視で強制配信
        if (isConfirmableNotification(notification.getNotificationType())) {
            sendViaWebSocket(notification);
            sendViaPush(notification, subscriptions);
            return true;
        }

        // スコープ別 opt-out（先読み Map・行なしは既定 ON）
        if (!scopeEnabledMap.getOrDefault(userId, Boolean.TRUE)) {
            return false;
        }
        // 種別別 opt-out（先読み Map・行なしは enum 既定値）
        if (!typeEnabledMap.getOrDefault(userId, typeDefaultEnabled)) {
            return false;
        }

        sendViaWebSocket(notification);
        sendViaPush(notification, subscriptions);
        return true;
    }

    /**
     * 配信対象通知に対する受信者の閲覧可否を判定する (F00 Phase F)。
     *
     * <p>fail-soft: {@code sourceType} が {@link ReferenceType} に解決できない、
     * または {@code sourceId} が null の通知は判定対象外として true を返す。
     *
     * @param notification 配信対象通知
     * @return アクセス可能または判定対象外なら true
     */
    private boolean isAccessibleForRecipient(NotificationEntity notification) {
        Long sourceId = notification.getSourceId();
        if (sourceId == null) {
            return true;
        }
        Optional<ReferenceType> refType =
                NotificationSourceTypeMapper.resolve(notification.getSourceType());
        if (refType.isEmpty()) {
            return true;
        }
        return visibilityChecker.canView(refType.get(), sourceId, notification.getUserId());
    }

    /**
     * 確認通知種別かどうかを判定する（opt-out 無視の強制配信対象）。
     * CONFIRMABLE_NOTIFICATION / CONFIRMABLE_NOTIFICATION_REMINDER_1 / CONFIRMABLE_NOTIFICATION_REMINDER_2
     * のすべてに対応するよう前方一致で判定する。
     */
    private boolean isConfirmableNotification(String notificationType) {
        return notificationType != null && notificationType.startsWith("CONFIRMABLE_NOTIFICATION");
    }

    /**
     * WebSocket (STOMP) 経由でリアルタイム通知を送信する。
     * クライアントは /user/{userId}/queue/notifications を購読する。
     */
    private void sendViaWebSocket(NotificationEntity notification) {
        try {
            NotificationResponse response = notificationMapper.toNotificationResponse(notification);
            messagingTemplate.convertAndSendToUser(
                    notification.getUserId().toString(),
                    "/queue/notifications",
                    response);
            log.debug("WebSocket通知送信: userId={}, notificationId={}",
                    notification.getUserId(), notification.getId());
        } catch (Exception e) {
            log.warn("WebSocket通知送信失敗: userId={}, error={}",
                    notification.getUserId(), e.getMessage());
        }
    }

    /**
     * PWA Push (Web Push API) 経由で通知を送信する。
     * VAPID署名によるHTTP Pushプロトコルを使用する。
     *
     * <p>F04.3: web-push-java ライブラリ（{@link WebPushService}）を使って
     * 各購読エンドポイントへ実際の HTTP Push を送信する。
     * 購読失効（410/404）時は {@link WebPushService} が自動で DB 削除を行う。
     *
     * <p>このメソッドは {@link #dispatch} から呼ばれる。{@code dispatch} 自体が
     * {@code @Async} で非同期実行されるため、このメソッドに {@code @Async} を付けると
     * 同一クラス内呼び出しで AOP プロキシが効かない問題が発生する。意図的に省略している。
     */
    private void sendViaPush(NotificationEntity notification) {
        sendViaPush(notification, pushSubscriptionService.listSubscriptions(notification.getUserId()));
    }

    /**
     * PWA Push を、<b>先読み済みの購読リスト</b>を使って送信する（{@link #dispatchBatch} 用）。
     * 単発 {@link #dispatch} 経路は購読を都度引く {@link #sendViaPush(NotificationEntity)} を用いる。
     */
    private void sendViaPush(NotificationEntity notification, List<PushSubscriptionEntity> subscriptions) {
        if (subscriptions == null || subscriptions.isEmpty()) {
            log.debug("プッシュ購読なし: userId={}", notification.getUserId());
            return;
        }

        String jsonPayload = buildPushPayload(notification);

        for (PushSubscriptionEntity subscription : subscriptions) {
            webPushService.sendPushNotification(subscription, jsonPayload);
        }
    }

    /**
     * 通知エンティティから Web Push ペイロード JSON を生成する。
     * 生成に失敗した場合はシンプルな代替 JSON を返す。
     */
    private String buildPushPayload(NotificationEntity notification) {
        try {
            NotificationResponse response = notificationMapper.toNotificationResponse(notification);
            return objectMapper.writeValueAsString(response);
        } catch (JsonProcessingException e) {
            log.warn("WebPushペイロードJSON生成失敗: notificationId={}, error={}",
                    notification.getId(), e.getMessage());
            // フォールバック: 最低限の情報を含む JSON
            return "{\"type\":\"" + notification.getNotificationType() + "\"}";
        }
    }
}
