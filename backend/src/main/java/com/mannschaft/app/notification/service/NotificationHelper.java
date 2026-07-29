package com.mannschaft.app.notification.service;

import com.mannschaft.app.common.visibility.ContentVisibilityChecker;
import com.mannschaft.app.common.visibility.NotificationSourceTypeMapper;
import com.mannschaft.app.common.visibility.ReferenceType;
import com.mannschaft.app.notification.NotificationPriority;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.credit.entity.NotificationSourceType;
import com.mannschaft.app.notification.credit.service.NotificationCreditService;
import com.mannschaft.app.notification.entity.NotificationEntity;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 通知発火ヘルパー。各モジュールから通知を簡便に作成・配信するためのファサード。
 *
 * <p>使用例:</p>
 * <pre>
 * notificationHelper.notify(userId, "SCHEDULE_REMINDER", "リマインド", "出欠未回答です",
 *         "SCHEDULE", scheduleId, NotificationScopeType.TEAM, teamId,
 *         "/schedules/" + scheduleId, actorId);
 * </pre>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationHelper {

    /**
     * fan-out 抜本改修 P1: 一括通知（{@link #notifyAllPreAuthorized}）を「受信者ごと 1 save」ではなく
     * <b>チャンク単位バルク INSERT ＋ チャンクコミット ＋ 専用プール配信</b>で処理するための書き込みファサード。
     * 単発 {@link #notify} 系は従来どおり {@link NotificationService} + {@link NotificationDispatchService} を用いる。
     */
    private static final int FANOUT_CHUNK_SIZE = 500;

    private final NotificationService notificationService;
    private final NotificationDispatchService dispatchService;
    private final NotificationBulkFanoutService bulkFanoutService;

    /**
     * F00 Phase F セキュリティガード (§11.1): 一括通知 ({@link #notifyAll})
     * の前段で受信者リストを {@link ContentVisibilityChecker#filterAccessible}
     * によりバッチで絞り込むために用いる。N+1 の {@code canView} ループを
     * 単一 batch クエリ 1 回で処理することで、大量受信者通知 (大会 / 回覧 /
     * 確認通知) における性能を担保しつつ漏れなくガードする。
     *
     * <p>fail-soft: ReferenceType 未対応の sourceType に対しては
     * 受信者リスト全件をそのまま通過させる (既存挙動の互換性確保)。
     */
    private final ContentVisibilityChecker visibilityChecker;

    /** F09.13 通知クレジットサービス（課金対象通知のカウント用） */
    private final NotificationCreditService notificationCreditService;

    /**
     * fan-out 抜本改修 P1: 一括通知のチャンク失敗を「静かに消さず数える」ための Micrometer レジストリ。
     * narrowed な test context にはレジストリが無いことがあるため {@link ObjectProvider} で optional 解決する
     * （{@code AsyncConfig#notification-fanout-pool} と同じ作法）。
     */
    private final ObjectProvider<MeterRegistry> meterRegistryProvider;

    /**
     * 単一ユーザーに通知を作成・配信する。
     *
     * <p>F00 Phase F: {@code createNotification} が visibility deny で
     * {@code null} を返した場合、配信もスキップする。
     */
    public void notify(Long userId, String notificationType, String title, String body,
                       String sourceType, Long sourceId,
                       NotificationScopeType scopeType, Long scopeId,
                       String actionUrl, Long actorId) {
        NotificationEntity notification = notificationService.createNotification(
                userId, notificationType, NotificationPriority.NORMAL,
                title, body, sourceType, sourceId, scopeType, scopeId, actionUrl, actorId);
        if (notification == null) {
            return;
        }
        dispatchService.dispatch(notification);
    }

    /**
     * 単一ユーザーに優先度指定で通知を作成・配信する。
     *
     * <p>F00 Phase F: {@code createNotification} が visibility deny で
     * {@code null} を返した場合、配信もスキップする。
     */
    public void notify(Long userId, String notificationType, NotificationPriority priority,
                       String title, String body,
                       String sourceType, Long sourceId,
                       NotificationScopeType scopeType, Long scopeId,
                       String actionUrl, Long actorId) {
        NotificationEntity notification = notificationService.createNotification(
                userId, notificationType, priority,
                title, body, sourceType, sourceId, scopeType, scopeId, actionUrl, actorId);
        if (notification == null) {
            return;
        }
        dispatchService.dispatch(notification);
    }

    /**
     * 複数ユーザーに一括通知を作成・配信する。
     *
     * <p>F00 Phase F: 受信者リストを事前に
     * {@link ContentVisibilityChecker#filterAccessible} で絞り込み、
     * 閲覧不可ユーザーには通知を作らない (§11.1 受信者リスト確定後の
     * 必須フィルタ)。
     */
    public void notifyAll(List<Long> userIds, String notificationType, String title, String body,
                          String sourceType, Long sourceId,
                          NotificationScopeType scopeType, Long scopeId,
                          String actionUrl, Long actorId) {
        notifyAll(userIds, notificationType, title, body, sourceType, sourceId,
                scopeType, scopeId, actionUrl, actorId, false, null);
    }

    /**
     * 複数ユーザーに一括通知を作成・配信する（課金フラグ付き）。
     *
     * <p>F09.13: {@code isBillable=true} かつ {@code organizationId} が指定された場合、
     * 送信前に {@link NotificationCreditService#consume} を呼び出してクレジットを消費する。</p>
     *
     * @param isBillable     課金対象フラグ（告知通知のみ true）
     * @param organizationId 課金対象の組織ID（isBillable=true の場合は必須）
     */
    public void notifyAll(List<Long> userIds, String notificationType, String title, String body,
                          String sourceType, Long sourceId,
                          NotificationScopeType scopeType, Long scopeId,
                          String actionUrl, Long actorId,
                          boolean isBillable, Long organizationId) {
        List<Long> filtered = filterAccessibleRecipients(userIds, sourceType, sourceId);

        // F09.13: 課金対象の場合はクレジット消費を先行実行（送信前ゲート）
        if (isBillable && organizationId != null && !filtered.isEmpty()) {
            notificationCreditService.consume(organizationId, filtered.size(), NotificationSourceType.NOTIFY_ALL);
        }

        for (Long userId : filtered) {
            try {
                notify(userId, notificationType, title, body,
                        sourceType, sourceId, scopeType, scopeId, actionUrl, actorId);
            } catch (Exception e) {
                log.warn("通知送信失敗（継続）: userId={}, type={}, error={}",
                        userId, notificationType, e.getMessage());
            }
        }
        log.info("一括通知送信: type={}, userCount={}（visibility絞込後）, isBillable={}",
                notificationType, filtered.size(), isBillable);
    }

    /**
     * 複数ユーザーに優先度指定で一括通知を作成・配信する。
     *
     * <p>F00 Phase F: 受信者リストを事前に
     * {@link ContentVisibilityChecker#filterAccessible} で絞り込み、
     * 閲覧不可ユーザーには通知を作らない (§11.1 受信者リスト確定後の
     * 必須フィルタ)。
     */
    public void notifyAll(List<Long> userIds, String notificationType, NotificationPriority priority,
                          String title, String body,
                          String sourceType, Long sourceId,
                          NotificationScopeType scopeType, Long scopeId,
                          String actionUrl, Long actorId) {
        notifyAll(userIds, notificationType, priority, title, body, sourceType, sourceId,
                scopeType, scopeId, actionUrl, actorId, false, null);
    }

    /**
     * 複数ユーザーに優先度指定で一括通知を作成・配信する（課金フラグ付き）。
     *
     * <p>F09.13: {@code isBillable=true} かつ {@code organizationId} が指定された場合、
     * 送信前に {@link NotificationCreditService#consume} を呼び出してクレジットを消費する。</p>
     *
     * @param isBillable     課金対象フラグ（告知通知のみ true）
     * @param organizationId 課金対象の組織ID（isBillable=true の場合は必須）
     */
    public void notifyAll(List<Long> userIds, String notificationType, NotificationPriority priority,
                          String title, String body,
                          String sourceType, Long sourceId,
                          NotificationScopeType scopeType, Long scopeId,
                          String actionUrl, Long actorId,
                          boolean isBillable, Long organizationId) {
        List<Long> filtered = filterAccessibleRecipients(userIds, sourceType, sourceId);

        // F09.13: 課金対象の場合はクレジット消費を先行実行（送信前ゲート）
        if (isBillable && organizationId != null && !filtered.isEmpty()) {
            notificationCreditService.consume(organizationId, filtered.size(), NotificationSourceType.NOTIFY_ALL);
        }

        for (Long userId : filtered) {
            try {
                notify(userId, notificationType, priority, title, body,
                        sourceType, sourceId, scopeType, scopeId, actionUrl, actorId);
            } catch (Exception e) {
                log.warn("通知送信失敗（継続）: userId={}, type={}, error={}",
                        userId, notificationType, e.getMessage());
            }
        }
        log.info("一括通知送信: type={}, priority={}, userCount={}（visibility絞込後）, isBillable={}",
                notificationType, priority, filtered.size(), isBillable);
    }

    /**
     * 配信認可済み受信者へ単一通知を作成・配信する（配信＝受信権 統一・関所(1)通知）。
     *
     * <p>{@link #notify(Long, String, NotificationPriority, String, String, String, Long,
     * NotificationScopeType, Long, String, Long)} と異なり、
     * {@link NotificationService#createNotificationPreAuthorized} を呼ぶため
     * visibility ガード（canView）を通さない。{@link #notifyAllPreAuthorized} のループ本体として用いる。</p>
     */
    public void notifyPreAuthorized(Long userId, String notificationType, NotificationPriority priority,
                                    String title, String body,
                                    String sourceType, Long sourceId,
                                    NotificationScopeType scopeType, Long scopeId,
                                    String actionUrl, Long actorId) {
        NotificationEntity notification = notificationService.createNotificationPreAuthorized(
                userId, notificationType, priority,
                title, body, sourceType, sourceId, scopeType, scopeId, actionUrl, actorId);
        dispatchService.dispatch(notification);
    }

    /**
     * 配信認可済み受信者リストへ一括通知を作成・配信する（配信＝受信権 統一・関所(1)通知）。
     *
     * <p>{@link #notifyAll} と異なり、{@link #filterAccessibleRecipients}（canView 絞り込み）を
     * <b>通さない</b>。受信者リストが「配信母集団（コンテンツの {@code includeSupporters} トグル準拠で
     * {@code resolveOrgDistributionUserIds} が展開した集合）」として呼び出し側で事前認可済みの場合にのみ使用する。</p>
     *
     * <p>これにより、SURVEY 通知が結果閲覧（ResultsVisibility）軸の canView で誤って deny され
     * 直属一般メンバー／配下チームメンバーへ届かなかった (B) 通知レグレッションを根治する。
     * 課金（{@code isBillable}）は告知通知専用の概念であり、本配信通知は対象外のため引数を持たない
     * （既存 {@link #notifyAll} の課金オーバーロードは不変）。</p>
     *
     * @param userIds          配信母集団で事前認可済みの受信者リスト
     * @param notificationType 通知種別
     * @param priority         優先度
     * @param title            タイトル
     * @param body             本文
     * @param sourceType       ソース種別
     * @param sourceId         ソースID
     * @param scopeType        通知スコープ種別
     * @param scopeId          通知スコープID
     * @param actionUrl        アクションURL
     * @param actorId          実行者ID
     */
    public void notifyAllPreAuthorized(List<Long> userIds, String notificationType, NotificationPriority priority,
                                       String title, String body,
                                       String sourceType, Long sourceId,
                                       NotificationScopeType scopeType, Long scopeId,
                                       String actionUrl, Long actorId) {
        notifyAllPreAuthorized(userIds, notificationType, priority, title, body,
                sourceType, sourceId, scopeType, scopeId, actionUrl, actorId, null);
    }

    /**
     * 配信認可済み受信者リストへ一括通知を作成・配信する（{@code organization_id} 充填版・fan-out 抜本改修 P1）。
     *
     * <p><b>実装（P1）</b>: 受信者を {@code FANOUT_CHUNK_SIZE} 件ごとに刻み、チャンク単位で
     * {@link NotificationBulkFanoutService#insertAndDispatchChunk バルク INSERT ＋ チャンクコミット ＋
     * 専用プール配信} する。従来の「受信者ごと 1 save ＋ 単発 dispatch」の N+1（INSERT・設定/種別/購読クエリ）を
     * 断ち、発行文数を受信者数でなくチャンク数に比例させる。呼び出し側が 50 万人規模の受信者を
     * キーセットページングでストリーム供給すれば、メモリ・ロック保持時間ともに有界になる。</p>
     *
     * <p><b>best-effort</b>: {@code null} 受信者は INSERT 前に除外する（現行は NOT NULL 違反で 1 件ずつ
     * スキップされていた挙動と同じ最終結果＝残りは作成される）。チャンク単位トランザクションのため、
     * データ起因の失敗は当該チャンクに限局する。</p>
     *
     * @param organizationId 組織ID（NULL 可・テナント絞り込み布石 4-B）
     * @see #notifyAllPreAuthorized(List, String, NotificationPriority, String, String, String, Long,
     *      NotificationScopeType, Long, String, Long)
     */
    public void notifyAllPreAuthorized(List<Long> userIds, String notificationType, NotificationPriority priority,
                                       String title, String body,
                                       String sourceType, Long sourceId,
                                       NotificationScopeType scopeType, Long scopeId,
                                       String actionUrl, Long actorId, Long organizationId) {
        if (userIds == null || userIds.isEmpty()) {
            return;
        }
        // best-effort: null 受信者は除外（NOT NULL 違反で 1 件ずつ落ちていた現行挙動と同じ最終結果）。
        List<Long> recipients = userIds.stream().filter(Objects::nonNull).toList();
        if (recipients.isEmpty()) {
            return;
        }

        int total = 0;
        int failedRecipients = 0;
        for (int from = 0; from < recipients.size(); from += FANOUT_CHUNK_SIZE) {
            int to = Math.min(from + FANOUT_CHUNK_SIZE, recipients.size());
            List<Long> chunk = recipients.subList(from, to);
            try {
                bulkFanoutService.insertAndDispatchChunk(chunk, notificationType, priority, title, body,
                        sourceType, sourceId, scopeType, scopeId, actionUrl, actorId, organizationId);
            } catch (Exception e) {
                // best-effort 契約: チャンク失敗を握って次チャンクへ継続し、呼び出し元の業務トランザクションを
                // 巻き添えロールバックさせない（同期呼び出し元＝予定リマインド・アンケート督促/締切延長 等が前提）。
                // 欠落は silent drop にせず、ログ＋メトリクスで可視化する（AC-8 の可視化思想と整合）。
                failedRecipients += chunk.size();
                log.error("一括通知チャンク失敗（best-effort継続・欠落を可視化）: type={}, chunkSize={}, error={}",
                        notificationType, chunk.size(), e.getMessage(), e);
                incrementChunkFailureMetric(chunk.size());
            }
            total += chunk.size();
        }
        log.info("一括通知送信(配信認可済・バルク): type={}, userCount={}, chunkSize={}, failedRecipients={}（visibility絞込なし）",
                notificationType, total, FANOUT_CHUNK_SIZE, failedRecipients);
    }

    /**
     * 一括通知のチャンク失敗件数を Micrometer で可視化する（silent drop の対極＝欠落を数える）。
     * レジストリが無い環境（narrowed test context 等）では何もしない。
     */
    private void incrementChunkFailureMetric(int recipientCount) {
        if (meterRegistryProvider == null) {
            return;
        }
        MeterRegistry registry = meterRegistryProvider.getIfAvailable();
        if (registry == null) {
            return;
        }
        registry.counter("mannschaft.notification.fanout.chunk_failed").increment(recipientCount);
    }

    /**
     * 配信認可済み受信者リストへ NORMAL 優先度で一括通知を作成・配信する（配信＝受信権 統一・関所(1)通知）。
     *
     * @see #notifyAllPreAuthorized(List, String, NotificationPriority, String, String, String, Long,
     *      NotificationScopeType, Long, String, Long)
     */
    public void notifyAllPreAuthorized(List<Long> userIds, String notificationType,
                                       String title, String body,
                                       String sourceType, Long sourceId,
                                       NotificationScopeType scopeType, Long scopeId,
                                       String actionUrl, Long actorId) {
        notifyAllPreAuthorized(userIds, notificationType, NotificationPriority.NORMAL, title, body,
                sourceType, sourceId, scopeType, scopeId, actionUrl, actorId);
    }

    /**
     * 受信者リストを visibility ガードで絞り込む (F00 Phase F)。
     *
     * <p>fail-soft: {@code sourceType} が {@link ReferenceType} に
     * 解決できない、または {@code sourceId} が null の通知は判定対象外として
     * 入力をそのまま返す。Resolver 配備済の type に対しては
     * 各受信者ごとに {@code canView} で個別判定する。
     *
     * @param userIds    候補受信者リスト
     * @param sourceType 通知 sourceType
     * @param sourceId   通知 sourceId
     * @return 閲覧可能と判定された受信者の絞込結果
     */
    private List<Long> filterAccessibleRecipients(List<Long> userIds, String sourceType, Long sourceId) {
        if (userIds == null || userIds.isEmpty() || sourceId == null) {
            return userIds;
        }
        Optional<ReferenceType> refType = NotificationSourceTypeMapper.resolve(sourceType);
        if (refType.isEmpty()) {
            return userIds;
        }
        // §7.1 現在の filterAccessible は (type, contentIds, userId) シグネチャで
        // 「単一 user に対する複数 content」のフィルタとなる。本ユースケースは
        // 「単一 content に対する複数 user」のため、各 user について canView を回す。
        // 将来 §11.1 §17 Q11 で API 拡張 (Resolver 側 batch by users) が入ったら
        // ここを置換する。
        ReferenceType type = refType.get();
        Long contentId = sourceId;
        return userIds.stream()
                .filter(uid -> {
                    boolean allowed = visibilityChecker.canView(type, contentId, uid);
                    if (!allowed) {
                        log.warn("通知受信者除外 (visibility deny): userId={}, refType={}, contentId={}",
                                uid, type, contentId);
                    }
                    return allowed;
                })
                .toList();
    }

    /**
     * テスト・デバッグ用に visibility 絞込関数を露出する。
     *
     * <p>本メソッドは {@link Set} を返さず {@link List} を返すことで
     * 元の挿入順序 (UI 上の通知順序の決定要因) を保つ。
     *
     * @param userIds    候補受信者リスト
     * @param sourceType 通知 sourceType
     * @param sourceId   通知 sourceId
     * @return 閲覧可能と判定された受信者の絞込結果
     */
    public List<Long> filterAccessibleForTest(List<Long> userIds, String sourceType, Long sourceId) {
        return filterAccessibleRecipients(userIds, sourceType, sourceId);
    }
}
