package com.mannschaft.app.notification.service;

import com.mannschaft.app.common.i18n.UserLocaleCache;
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
import java.util.Locale;
import java.util.Map;
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
     * Issue #2715 検分是正: {@link #notifyAllLocalized} が受信者ごとの locale を
     * <b>一括</b>解決するために用いる（受信者数に比例した DB 往復＝N+1 を防ぐ）。
     */
    private final UserLocaleCache userLocaleCache;

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
    public int notifyAllPreAuthorized(List<Long> userIds, String notificationType, NotificationPriority priority,
                                       String title, String body,
                                       String sourceType, Long sourceId,
                                       NotificationScopeType scopeType, Long scopeId,
                                       String actionUrl, Long actorId) {
        return notifyAllPreAuthorized(userIds, notificationType, priority, title, body,
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
    public int notifyAllPreAuthorized(List<Long> userIds, String notificationType, NotificationPriority priority,
                                       String title, String body,
                                       String sourceType, Long sourceId,
                                       NotificationScopeType scopeType, Long scopeId,
                                       String actionUrl, Long actorId, Long organizationId) {
        if (userIds == null || userIds.isEmpty()) {
            return 0;
        }
        // best-effort: null 受信者は除外（NOT NULL 違反で 1 件ずつ落ちていた現行挙動と同じ最終結果）。
        List<Long> recipients = userIds.stream().filter(Objects::nonNull).toList();
        if (recipients.isEmpty()) {
            return 0;
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
        // Codex 三巡目是正（PR #2873）: 呼び出し元（notifyAllPreAuthorizedLocalized 等）が
        // 「例外が飛ばなかった＝成功」と誤集計しないよう、実際の失敗件数を返す
        // （insertAndDispatchChunk はチャンク単位で失敗を握って正常 return する best-effort 契約のため、
        // 呼び出し元は戻り値でしか欠落を判定できない）。
        return failedRecipients;
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
    public int notifyAllPreAuthorized(List<Long> userIds, String notificationType,
                                       String title, String body,
                                       String sourceType, Long sourceId,
                                       NotificationScopeType scopeType, Long scopeId,
                                       String actionUrl, Long actorId) {
        return notifyAllPreAuthorized(userIds, notificationType, NotificationPriority.NORMAL, title, body,
                sourceType, sourceId, scopeType, scopeId, actionUrl, actorId);
    }

    /**
     * 受信者ごとに locale の異なる本文で一括通知を作成・配信する。
     *
     * <p>Issue #2715 ロットA 検分是正（PR #2764）: 「notifyAll（単一文面の一括配信）を受信者別
     * locale で本文を変えたいがために notify の逐次ループへ置換する」というロットB・C でも
     * 繰り返される要求に対し、<b>呼び出し側で個別対応させず本メソッドへ一本化する</b>。</p>
     *
     * <p><b>訂正（2026-08-14）</b>: 当初の検分では「notify の逐次ループは F00 Phase F の可視性
     * フィルタを迂回し情報漏洩を招く退行である」としていたが、これは誤りだった。
     * {@link NotificationService#createNotification} 自身が単発経路でも
     * {@code isAccessible}（canView）による可視性ガードを既に担保しており（
     * {@code NotificationService.java:314} 付近）、{@link #notify} をループで直呼びしても
     * 閲覧不可ユーザーへ通知が作られることは無かった。本メソッドの本当の意義は以下の 3 点であり、
     * 「これが無いと漏洩する」という主張はしない（可視性ガードは
     * {@code createNotification} と {@link #filterAccessibleRecipients} の多層防御であり、
     * 本メソッドはそのうちの前段の一層を提供するに過ぎない）。</p>
     * <ol>
     *   <li><b>一括経路でも受信者別 locale を扱えるようにすること</b>: 既存 {@link #notifyAll}
     *       は単一文面固定のため、受信者ごとに異なる locale で本文を組み立てるユースケースに
     *       対応できなかった。</li>
     *   <li><b>locale の一括解決による N+1 回避</b>: {@link UserLocaleCache#getLocales} で
     *       1 クエリにまとめる。受信者ごとに {@link UserLocaleCache#getLocale} を呼ぶと、
     *       大量受信者配信（例: FOLLOWERS 配信の {@code PageRequest.of(0, 10000)}）で
     *       キャッシュコールド時に最大受信者数分の DB 往復が公開トランザクション内に発生する。</li>
     *   <li><b>前段フィルタによる無駄な処理の削減</b>: 先頭で {@link #filterAccessibleRecipients}
     *       を通して閲覧不可ユーザーをあらかじめ除外することで、どのみち {@code createNotification}
     *       内の可視性ガードで捨てられるユーザー分の本文組み立て・{@code notify} 呼び出しを
     *       無駄に行わずに済む。</li>
     * </ol>
     *
     * <p>絞り込み・locale 解決後の受信者ごとに {@code bodyBuilder} で本文を組み立てて {@link #notify}
     * を呼ぶ。1 件の失敗が他の宛先を巻き込まないよう try/catch + {@code log.warn} する
     * （既存 {@link #notifyAll} と同じ作法）。</p>
     *
     * @param userIds          候補受信者リスト（visibility 絞込前）
     * @param notificationType 通知種別
     * @param sourceType       ソース種別（visibility フィルタの解決キーにもなる）
     * @param sourceId         ソースID
     * @param scopeType        通知スコープ種別
     * @param scopeId          通知スコープID
     * @param actionUrl        アクションURL
     * @param actorId          実行者ID
     * @param bodyBuilder      (userId, locale) → タイトル・本文 を組み立てる関数（呼び出し側で
     *                         {@code MessageSource} を用いて i18n 解決したものを返す）
     */
    public void notifyAllLocalized(List<Long> userIds, String notificationType,
                                   String sourceType, Long sourceId,
                                   NotificationScopeType scopeType, Long scopeId,
                                   String actionUrl, Long actorId,
                                   LocalizedMessageBuilder bodyBuilder) {
        List<Long> filtered = filterAccessibleRecipients(userIds, sourceType, sourceId);
        if (filtered.isEmpty()) {
            log.info("一括通知送信(locale別): type={}, userCount=0（visibility絞込後）", notificationType);
            return;
        }

        // locale を一括解決（N+1 防止）。
        Map<Long, String> locales = userLocaleCache.getLocales(filtered);

        int successCount = 0;
        for (Long userId : filtered) {
            try {
                Locale locale = Locale.forLanguageTag(locales.getOrDefault(userId, "ja"));
                LocalizedMessage message = bodyBuilder.build(userId, locale);
                notify(userId, notificationType, message.title(), message.body(),
                        sourceType, sourceId, scopeType, scopeId, actionUrl, actorId);
                successCount++;
            } catch (Exception e) {
                log.warn("通知送信失敗（継続）: userId={}, type={}, error={}",
                        userId, notificationType, e.getMessage());
            }
        }
        log.info("一括通知送信(locale別): type={}, userCount={}（visibility絞込後）, successCount={}",
                notificationType, filtered.size(), successCount);
    }

    /**
     * 配信認可済み受信者リストへ、受信者ごとに locale の異なる本文で一括通知を作成・配信する。
     *
     * <p>Issue #2715 CMP-055 ロットC-5: {@link #notifyAllPreAuthorized} と同様に
     * {@link #filterAccessibleRecipients}（canView 絞り込み）を<b>通さない</b>。
     * 受信者リストが呼び出し側で事前認可済みの配信母集団である点は {@link #notifyAllPreAuthorized}
     * と同じで、{@link #notifyAllLocalized} との違いは locale 別に本文を組み立てる点のみ。</p>
     *
     * <p><b>是正（Codex 検分・PR #2873）</b>: 当初実装は受信者ごとに {@link #notifyPreAuthorized}
     * （1 件 1 save）を呼ぶループだったため、locale の N+1 は防げていても fan-out 抜本改修 P1
     * （{@link #notifyAllPreAuthorized} のチャンク単位バルク INSERT）から外れ、より重い
     * 「通知永続化・配信の N+1」を作り込んでいた。受信者を locale ごとにグループ化し、各グループを
     * {@link #notifyAllPreAuthorized}（バルク版）へ渡すことで、実在ロケール数（高々 7 種）分の
     * バルク呼び出しに畳む。</p>
     *
     * @param userIds          配信母集団で事前認可済みの受信者リスト
     * @param bodyBuilder      (userId, locale) → タイトル・本文 を組み立てる関数。呼び出し元 3 箇所
     *                         （SurveyPublishNotificationListener/SurveyRemindService/SurveyService）は
     *                         いずれも {@code userId} を使わず {@code locale} のみで文面を決めるため、
     *                         locale 単位で 1 回だけ組み立てて安全（呼び出し側の userId 依存が
     *                         生じた場合は本メソッドの locale グループ化と両立しなくなるため要再検討）。
     */
    public void notifyAllPreAuthorizedLocalized(List<Long> userIds, String notificationType,
                                                String sourceType, Long sourceId,
                                                NotificationScopeType scopeType, Long scopeId,
                                                String actionUrl, Long actorId,
                                                LocalizedMessageBuilder bodyBuilder) {
        if (userIds == null || userIds.isEmpty()) {
            return;
        }
        // Codex 四巡目是正（PR #2873）: notifyAllPreAuthorized は先頭で null 受信者を除外し
        // （NOT NULL 違反で 1 件ずつ落ちていた現行挙動と同じ最終結果）、null のみのグループは
        // 1 件も INSERT せず failedRecipients=0 で正常 return する。グループ化より前に
        // null を除外しておかないと、下流の集計基準（有効受信者数）とここでの group.size()
        // （null 込みの元の要素数）がズレ、null 受信者を「配信成功」扱いで誤計上してしまう。
        List<Long> recipients = userIds.stream().filter(java.util.Objects::nonNull).toList();
        if (recipients.isEmpty()) {
            return;
        }
        // locale を一括解決（N+1 防止）。
        Map<Long, String> locales = userLocaleCache.getLocales(recipients);

        // 受信者を locale ごとにグループ化し、各グループをバルク経路（notifyAllPreAuthorized）へ渡す。
        Map<String, List<Long>> byLocale = new java.util.LinkedHashMap<>();
        for (Long userId : recipients) {
            String localeTag = locales.getOrDefault(userId, "ja");
            byLocale.computeIfAbsent(localeTag, k -> new java.util.ArrayList<>()).add(userId);
        }

        // Codex 再検分是正（PR #2873）: locale グループ化により、元の受信者ごとの try/catch
        // （best-effort）が失われ、ある locale の bodyBuilder（MessageFormat エラー等の非DB例外）が
        // 一度失敗すると後続の locale グループへ処理が進まず、呼び出し元の @Transactional
        // （SurveyRemindService#remind / SurveyService#extendDeadline 等）を巻き添えにしていた。
        // グループ単位で try/catch し、失敗を握って次のグループへ継続する（欠落は log.warn で可視化）。
        //
        // rollback-only について（Issue #2990 で実測し直した現状）:
        // 本メソッドの下流はバルク経路（notifyAllPreAuthorized → NotificationBulkFanoutService）であり、
        // チャンク INSERT は TransactionTemplate に PROPAGATION_REQUIRES_NEW を設定した独立トランザクション
        // （NotificationBulkFanoutService の 72-73 行で設定、173 行で実行）で走る。したがって
        // 通知の DB 層例外が呼び出し元の業務トランザクションへ rollback-only を伝播することはない。
        // ここでの catch は、その独立トランザクションの外側で起きる非DB例外（bodyBuilder の
        // MessageFormat エラー等）を受け止めて次の locale グループへ進むためのものである。
        //
        // ただし本クラスの非バルク経路（notify / notifyAll / notifyAllLocalized）は
        // NotificationService#createNotification を呼び出し元と同一トランザクションで実行するため、
        // 依然として rollback-only を伝播する。それらの呼び出し元は業務TX内で通知を発火せず、
        // AFTER_COMMIT 境界の後へ移すこと（契約: backend/.claudecode.md 原則5 / 原則5-1、
        // 番人: common/architecture/NotificationTransactionBoundaryGuardTest）。
        //
        // Codex 三巡目・四巡目是正を経て評価（PR #2873）: 当初は notifyAllPreAuthorized の戻り値
        // （失敗受信者数）を deliveredRecipientCount / failedRecipientCount として再集計していたが、
        // これは notifyAllPreAuthorized 自身が既に自前のログ（failedRecipients）・チャンク失敗の
        // log.error・Micrometer メトリクス（chunk_failed）で正確に報告している値の再集計に過ぎず、
        // 新しい一次情報を持たないまま実装ミスの発生源になっていた（3巡目: 全滅を成功に誤集計、
        // 4巡目: null 受信者を成功に誤集計）。そのため配信の成否そのものはここでは数えず、
        // 下位（notifyAllPreAuthorized 側）のログ・メトリクスへ委ねる。
        // 一方 messageBuiltGroupCount / messageBuildFailedGroupCount は、失敗が「locale の文面組み立て
        // （bodyBuilder＝MessageFormat エラー等）」側か「DB INSERT」側かという、下位層には持ち得ない
        // 一次情報を捉えるため残す。
        int messageBuiltGroupCount = 0;
        int messageBuildFailedGroupCount = 0;
        for (Map.Entry<String, List<Long>> entry : byLocale.entrySet()) {
            List<Long> group = entry.getValue();
            try {
                Locale locale = Locale.forLanguageTag(entry.getKey());
                // locale ごとに 1 回だけ本文を組み立てる（userId は使われない前提。上記 javadoc 参照）。
                LocalizedMessage message = bodyBuilder.build(group.get(0), locale);
                messageBuiltGroupCount++;
                notifyAllPreAuthorized(group, notificationType, NotificationPriority.NORMAL,
                        message.title(), message.body(),
                        sourceType, sourceId, scopeType, scopeId, actionUrl, actorId);
            } catch (Exception e) {
                // bodyBuilder（MessageFormat エラー等）の失敗はグループ全体が未着手のまま次へ進む。
                messageBuildFailedGroupCount++;
                log.warn("locale グループの通知送信失敗（継続）: type={}, locale={}, recipientCount={}, error={}",
                        notificationType, entry.getKey(), group.size(), e.getMessage(), e);
            }
        }
        // 配信の成否（届いた/欠落した件数）はここでは数えない: notifyAllPreAuthorized が自前のログ
        // （failedRecipients）・チャンク失敗の log.error・Micrometer メトリクスで既に正確に報告している。
        log.info("一括通知送信(配信認可済・locale別): type={}, userCount={}, effectiveRecipientCount={}, "
                        + "localeGroupCount={}, messageBuiltGroupCount={}, messageBuildFailedGroupCount={}"
                        + "（配信結果は notifyAllPreAuthorized 側のログ・メトリクス参照）",
                notificationType, userIds.size(), recipients.size(), byLocale.size(),
                messageBuiltGroupCount, messageBuildFailedGroupCount);
    }

    /** {@link #notifyAllLocalized} が受信者ごとに組み立てるタイトル・本文の組。 */
    public record LocalizedMessage(String title, String body) {
    }

    /** {@link #notifyAllLocalized} の本文組み立て関数（呼び出し側が i18n 解決を担う）。 */
    @FunctionalInterface
    public interface LocalizedMessageBuilder {
        LocalizedMessage build(Long userId, Locale locale);
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
