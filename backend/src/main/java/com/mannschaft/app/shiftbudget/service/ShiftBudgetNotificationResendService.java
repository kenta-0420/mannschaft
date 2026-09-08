package com.mannschaft.app.shiftbudget.service;

import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.service.NotificationHelper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * F08.7 {@code NOTIFICATION_SEND} 失敗イベントの再送（Issue #2990 L13）。
 *
 * <h2>監査済み例外である理由</h2>
 * <p>本クラスは「業務処理に<b>付随</b>する通知」ではなく、<b>通知を出すこと自体がユースケースの本体</b>である
 * （運用者が管理 API / リトライバッチから「あの通知をもう一度送れ」と命じる経路）。したがって
 * 番人 {@code NotificationTransactionBoundaryGuardTest} の {@code AUDITED_EXCEPTIONS} に登録し、
 * 「業務コミット後に配送せよ」という契約の適用対象から外す。
 * リトライ結果は同期 API {@code ShiftBudgetFailedEventService#retry} の応答
 * （success / 新ステータス）としてそのまま返す契約なので、{@code AFTER_COMMIT} へ移すと
 * 応答時点で結果が確定せず API 契約が壊れる。</p>
 *
 * <h2>{@code NOT_SUPPORTED} である理由（是正の本体）</h2>
 * <p>是正前は {@code ShiftBudgetRetryExecutor#retryNotificationSend} が
 * {@code @Transactional(REQUIRES_NEW)} な {@code execute} の内側で
 * {@code notificationHelper.notifyAll(...)} を呼んでいた。その下流
 * {@code NotificationService#createNotification} は既定の {@code REQUIRED} 伝播で
 * {@code execute} のトランザクションに参加するため、通知の DB 例外はそのトランザクションを
 * <b>rollback-only</b> にする。{@code execute} の catch は例外を握って
 * {@code entity.markFailed(...)} + {@code save} を行うが、commit 時に
 * {@code UnexpectedRollbackException} となり<b>着手マーク（{@code retry_count++}）も
 * FAILED / EXHAUSTED 化もまとめて消える</b>。失敗イベントは {@code PENDING} かつ
 * {@code retry_count} 据え置きのまま残るので、{@code MAX_RETRY} に永久に到達せず
 * 15 分毎のリトライバッチが同じイベントを無限に拾い続ける。しかも例外は
 * {@code execute} の catch の<b>外側</b>（プロキシの commit）で起きるため
 * {@code ShiftBudgetFailedEventService#retry} まで伝播し、その業務トランザクション
 * （監査ログ {@code FAILED_EVENT_RETRIED}）も巻き添えにして管理 API が 500 になる。</p>
 *
 * <p>{@link Propagation#NOT_SUPPORTED} は呼び出し元のトランザクションを<b>中断</b>するので、
 * 通知の DB 例外はもはや {@code execute} のトランザクションを汚さない。例外そのものは
 * 同期呼び出しなので {@code execute} の catch へ返り、そこで {@code markFailed} が
 * <b>コミットできる</b>ようになる（＝リトライ回数が正しく積み上がる）。</p>
 *
 * <h2>受信者ごとの被害半径と、失敗の可視化</h2>
 * <p>是正前の {@code notificationHelper.notifyAll} は受信者ごとの失敗を内部で
 * {@code log.warn} して握りつぶし、呼び出し元へは<b>何も返さなかった</b>。そのため
 * 受信者全員への再送が失敗しても失敗イベントは {@code SUCCEEDED} になり、
 * 「再送したのに誰にも届いていない」ことが運用者に見えなかった。
 * 本クラスは受信者ごとに {@code notify} を呼んで個別に握り（＝1 名の失敗が
 * 残りの受信者を巻き添えにしない）、<b>1 名でも失敗したら最後にまとめて例外を投げる</b>。
 * {@code execute} はそれを掴んで失敗イベントを FAILED として記録する。</p>
 *
 * <p>再送が部分的に成功した場合、次回リトライで成功済みの受信者へ重複配信されうる。
 * これは是正前（{@code notifyAll} が受信者単位で握りつぶす形）でも同じであり、
 * 「誰にも届いていないのに成功扱い」より重複のほうが害が小さいため、この割り切りを維持する。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShiftBudgetNotificationResendService {

    private final NotificationHelper notificationHelper;
    private final UserLocaleCache userLocaleCache;
    private final MessageSource messageSource;

    /**
     * 失敗した通知を受信者ごとに再送する。
     *
     * <h2>Issue #2908: リトライ経路だけ日本語固定だった問題の是正</h2>
     * <p>是正前は failed_events の payload に保存された {@code title} / {@code body}
     * （発火時に {@code Locale.JAPANESE} で確定させた文字列）をそのまま
     * {@code notifyAll} で再送していたため、正常系はロケール別に届くのに
     * <b>リトライ経路だけ英語利用者にも日本語が届いていた</b>。
     * payload には i18n キー（{@code title_key} / {@code body_key}）と
     * {@code threshold_percent} が入っているので、受信者ごとに本文を組み立て直す。
     * payload の {@code title} / {@code body} は運用ログ・フォレンジック用として残し、
     * 再送には使わない。</p>
     *
     * <p>{@code notifyAllLocalized} をそのまま使わないのは、あちらが受信者ごとの失敗を
     * 内部で握って呼び出し元へ何も返さないためである（＝全員に届かなくても SUCCEEDED になる）。
     * locale の一括解決は {@link UserLocaleCache#getLocales} を直接呼んで N+1 を避ける。</p>
     *
     * @param userIds          受信者ユーザーID
     * @param type             通知種別
     * @param titleKey         件名の i18n キー（{@code null} なら {@code fallbackTitle} をそのまま使う。
     *                         Issue #2908 より前に保存された旧 payload 対応）
     * @param bodyKey          本文の i18n キー（{@code null} なら {@code fallbackBody} をそのまま使う）
     * @param messageArgument  メッセージのプレースホルダ引数（閾値パーセント）
     * @param fallbackTitle    ロケールファイルにキーが無い場合の件名
     * @param fallbackBody     ロケールファイルにキーが無い場合の本文
     * @param sourceType       ソース種別
     * @param sourceId         ソースID
     * @param scopeId          通知スコープID（組織）
     * @param actionUrl        アクションURL
     * @throws IllegalStateException 1 名でも再送に失敗した場合（呼び出し元がリトライ失敗として記録する）
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void resend(List<Long> userIds, String type,
                       String titleKey, String bodyKey, Object messageArgument,
                       String fallbackTitle, String fallbackBody,
                       String sourceType, Long sourceId, Long scopeId, String actionUrl) {
        if (userIds.isEmpty()) {
            return;
        }
        Map<Long, String> locales = userLocaleCache.getLocales(userIds);
        Object[] args = {messageArgument};
        List<Long> failedUserIds = new ArrayList<>();
        for (Long userId : userIds) {
            try {
                Locale locale = Locale.forLanguageTag(locales.getOrDefault(userId, "ja"));
                notificationHelper.notify(
                        userId, type,
                        titleKey == null ? fallbackTitle
                                : messageSource.getMessage(titleKey, args, fallbackTitle, locale),
                        bodyKey == null ? fallbackBody
                                : messageSource.getMessage(bodyKey, args, fallbackBody, locale),
                        sourceType, sourceId,
                        NotificationScopeType.ORGANIZATION, scopeId,
                        actionUrl, null);
            } catch (Exception e) {
                // 1 名の失敗で残りの受信者を諦めない（被害半径の分離）。
                failedUserIds.add(userId);
                log.error("F08.7 通知再送に失敗（当該受信者のみスキップして継続）: userId={}, type={}, sourceId={}",
                        userId, type, sourceId, e);
            }
        }
        if (!failedUserIds.isEmpty()) {
            throw new IllegalStateException(
                    "Notification resend failed for " + failedUserIds.size() + "/" + userIds.size()
                            + " recipients: " + failedUserIds);
        }
        log.info("F08.7 通知再送に成功: type={}, sourceId={}, recipients={}", type, sourceId, userIds.size());
    }
}
