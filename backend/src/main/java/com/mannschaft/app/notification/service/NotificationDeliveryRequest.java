package com.mannschaft.app.notification.service;

import com.mannschaft.app.notification.NotificationPriority;
import com.mannschaft.app.notification.NotificationScopeType;

/**
 * 通知配送要求（Issue #2834 / CMP-056: 通知トランザクション分離の型確立PR）。
 *
 * <p>業務サービスは本レコードを組み立てて {@code ApplicationEventPublisher} 経由で
 * ドメインイベントとして発行するだけに留め、{@link NotificationService#createNotification}
 * を直接呼ばない。実際の生成・配信は業務コミット後（{@code AFTER_COMMIT}）に非同期リスナーが
 * 受け取り、{@link NotificationDeliveryRunner#sendOne} へ1件ずつ委譲する。</p>
 *
 * <h2>なぜ共通レコードにしたか</h2>
 * <p>{@code createNotification} のシグネチャ（宛先・種別・優先度・件名・本文・ソース・スコープ・
 * アクションURL・実行者）は既にドメイン非依存であり、ドメインごとに専用イベント型を用意すると
 * フィールドの重複が18箇所ぶん発生する。本レコードを各ドメインイベント（例:
 * {@code ContactRequestNotificationEvent}）が保持することで、配送 Runner・配送リスナーの実装を
 * 1本に集約できる。</p>
 *
 * @param recipientUserId  宛先ユーザーID
 * @param notificationType 通知種別
 * @param priority         優先度
 * @param title             タイトル
 * @param body              本文
 * @param sourceType        ソース種別（visibility ガードの解決キー。§11.1）
 * @param sourceId          ソースID（{@code null} 可）
 * @param scopeType         スコープ種別
 * @param scopeId           スコープID
 * @param actionUrl         アクションURL
 * @param actorId           実行者ID
 */
public record NotificationDeliveryRequest(
        Long recipientUserId,
        String notificationType,
        NotificationPriority priority,
        String title,
        String body,
        String sourceType,
        Long sourceId,
        NotificationScopeType scopeType,
        Long scopeId,
        String actionUrl,
        Long actorId) {
}
