package com.mannschaft.app.notification.credit.service;

import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.service.NotificationHelper;
import com.mannschaft.app.role.service.RoleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSource;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

/**
 * 通知クレジット関連アラートの非同期送信 Bean（CMP-035 / Issue #2990 L4）。
 *
 * <p>{@code @Async} はプロキシ経由の呼び出しでのみ有効になるため、呼び出し元と別 Bean に切り出す
 * （同一 Bean 内の自己呼び出しではプロキシを経由せず {@code @Async} が無視されるため）。</p>
 *
 * <h2>Issue #2990 L4: executor の明示と、期限アラート2種の移設</h2>
 * <p>是正前は {@code @Async} が executor 無指定で {@code @Primary} の {@code event-pool} へ暗黙に
 * 載っていた。番人（原則5）が要求する明示指定に揃え {@code event-pool} を明記する。</p>
 * <p>あわせて {@code NotificationCreditExpiryBatch} が<b>自クラス内で</b>宣言し自己呼び出ししていた
 * 期限アラート2種（{@code sendExpiryAlertAsync} / {@code sendCreditExpiredAlertAsync}）を本 Bean へ
 * 移設した。移設前はプロキシを経ないため {@code @Async} が失効し、バッチの単一
 * {@code @Transactional} の内側で<b>同期実行</b>されていた（詳細は
 * {@link com.mannschaft.app.notification.credit.batch.NotificationCreditExpiryBatch} の javadoc）。</p>
 *
 * <h2>呼び出し規約: 業務トランザクションの外から呼ぶこと</h2>
 * <p>本 Bean のメソッドはいずれも「項目TXの完了を待つ非トランザクションのバッチオーケストレータ」
 * からのみ呼ぶこと（凍結台帳ヘッダの契約）。{@code @Transactional} の内側から呼ぶと、
 * {@code @Async} の投入が拒否された場合（{@code event-pool} は AbortPolicy）に例外が業務TXへ
 * 波及しうる。</p>
 *
 * <h2>投入拒否で通知を失わないための {@code *Now} 系（Issue #2990 L4 検分是正）</h2>
 * <p>{@code event-pool} は AbortPolicy であり、飽和時の {@code RejectedExecutionException} は
 * {@code @Async} メソッド<b>本体の try/catch より前</b>（プロキシによる投入時点）で発生する。
 * つまり本体の catch では拾えない。呼び出し元が「送ったつもり」で先へ進むと通知は永久に失われる。
 * そのため各 {@code @Async} メソッドは本体を {@code *Now}（{@code @Async} 無し = 同期）へ切り出し、
 * 呼び出し元が拒否を捕らえて同期実行にフォールバックできるようにしてある。
 * 投入拒否は「タスクが一度も実行されていない」ことが保証される事象なので、
 * フォールバックしても二重送信にはならない。</p>
 *
 * <h2>D-5: 越境は Service 経由</h2>
 * <p>ADMIN ユーザーIDの解決は {@code role} ドメインの {@code UserRoleRepository} を直接注入せず
 * {@link RoleService#getAdminUserIdsByOrganizationId}（同リポジトリへの純粋な委譲）を使う。
 * 移設前のバッチは Repository を直接注入していたため、移設に伴い Service 経由へ揃えた。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationCreditAlertSender {

    private final NotificationHelper notificationHelper;
    private final RoleService roleService;
    /** Issue #2715 CMP-055 ロットC-1: 通知本文の i18n。locale 解決自体は notifyAllLocalized 内部の UserLocaleCache が担う。 */
    private final MessageSource messageSource;

    /**
     * 残高マイナス警告をADMINへ非同期送信する。
     *
     * @param organizationId 組織ID
     * @param creditBalance  現在のクレジット残高（負の値）
     */
    @Async("event-pool")
    public void sendNegativeBalanceAlert(Long organizationId, Long creditBalance) {
        try {
            List<Long> adminUserIds = roleService.getAdminUserIdsByOrganizationId(organizationId);
            if (adminUserIds.isEmpty()) {
                return;
            }
            notificationHelper.notifyAllLocalized(
                    adminUserIds,
                    "NOTIFICATION_CREDIT_NEGATIVE",
                    "NOTIFICATION_CREDIT",
                    organizationId,
                    NotificationScopeType.ORGANIZATION,
                    organizationId,
                    "/organizations/" + organizationId + "/settings/notification-credits",
                    null,
                    (userId, locale) -> new NotificationHelper.LocalizedMessage(
                            messageSource.getMessage(
                                    "notification.credit.negativeBalance.title", null,
                                    "通知クレジット残高がマイナスです", locale),
                            messageSource.getMessage(
                                    "notification.credit.negativeBalance.body",
                                    new Object[]{creditBalance},
                                    "猶予期間中の超過分が相殺された結果、クレジット残高がマイナスになりました。"
                                            + "残高: " + creditBalance + "通。クレジットを購入してください。", locale))
            );
            log.info("残高マイナスアラート送信: organizationId={}, balance={}", organizationId, creditBalance);
        } catch (Exception e) {
            log.error("残高マイナスアラート送信失敗: organizationId={}", organizationId, e);
        }
    }

    /**
     * 有効期限アラートをADMINへ非同期送信する。
     *
     * <p>Issue #2990 L4 で {@code NotificationCreditExpiryBatch} から移設。移設前は同バッチ内の
     * {@code @Async protected} メソッドを自己呼び出ししており、プロキシを経ないため非同期化が失効し、
     * バッチの単一 {@code @Transactional} の内側で同期実行されていた。</p>
     *
     * @param organizationId 組織ID
     * @param purchaseId     購入ID
     * @param expiresOn      有効期限日
     * @param daysRemaining  残り日数
     */
    @Async("event-pool")
    public void sendExpiryAlert(Long organizationId, Long purchaseId,
                                LocalDate expiresOn, int daysRemaining) {
        sendExpiryAlertNow(organizationId, purchaseId, expiresOn, daysRemaining);
    }

    /**
     * 有効期限アラートを<b>呼び出しスレッドで同期送信する</b>（{@code event-pool} 投入拒否時のフォールバック）。
     *
     * <p>{@link #sendExpiryAlert} の本体。{@code @Async} を付けないため、プロキシ経由で呼んでも
     * その場で実行される。バッチ側は {@code RejectedExecutionException} を捕らえてこちらを呼び直す
     * （経緯は {@code NotificationCreditExpiryBatch#sendOrFallback} の javadoc を参照）。</p>
     *
     * @param organizationId 組織ID
     * @param purchaseId     購入ID
     * @param expiresOn      有効期限日
     * @param daysRemaining  残り日数
     */
    public void sendExpiryAlertNow(Long organizationId, Long purchaseId,
                                   LocalDate expiresOn, int daysRemaining) {
        try {
            List<Long> adminUserIds = roleService.getAdminUserIdsByOrganizationId(organizationId);
            if (adminUserIds.isEmpty()) {
                return;
            }
            notificationHelper.notifyAllLocalized(
                    adminUserIds,
                    "NOTIFICATION_CREDIT_EXPIRY_ALERT",
                    "NOTIFICATION_CREDIT",
                    organizationId,
                    NotificationScopeType.ORGANIZATION,
                    organizationId,
                    "/organizations/" + organizationId + "/settings/notification-credits",
                    null,
                    (userId, locale) -> new NotificationHelper.LocalizedMessage(
                            messageSource.getMessage(
                                    "notification.credit.expiryAlert.title",
                                    new Object[]{daysRemaining},
                                    "通知クレジットの有効期限まで残り" + daysRemaining + "日です", locale),
                            messageSource.getMessage(
                                    "notification.credit.expiryAlert.body",
                                    new Object[]{purchaseId, expiresOn},
                                    "購入ID#" + purchaseId + "の通知クレジットが "
                                            + expiresOn + " に失効します。期限前にご利用ください。",
                                    locale))
            );
        } catch (Exception e) {
            log.error("有効期限アラート送信失敗: organizationId={}, purchaseId={}", organizationId, purchaseId, e);
        }
    }

    /**
     * クレジット失効通知をADMINへ非同期送信する。
     *
     * <p>Issue #2990 L4 で {@code NotificationCreditExpiryBatch} から移設（移設理由は
     * {@link #sendExpiryAlert} と同じ）。</p>
     *
     * @param organizationId 組織ID
     * @param expiredCredits 失効したクレジット通数
     */
    @Async("event-pool")
    public void sendCreditExpiredAlert(Long organizationId, long expiredCredits) {
        sendCreditExpiredAlertNow(organizationId, expiredCredits);
    }

    /**
     * クレジット失効通知を<b>呼び出しスレッドで同期送信する</b>（{@code event-pool} 投入拒否時のフォールバック）。
     *
     * <p>{@link #sendCreditExpiredAlert} の本体。用途は {@link #sendExpiryAlertNow} と同じ。</p>
     *
     * @param organizationId 組織ID
     * @param expiredCredits 失効したクレジット通数
     */
    public void sendCreditExpiredAlertNow(Long organizationId, long expiredCredits) {
        try {
            List<Long> adminUserIds = roleService.getAdminUserIdsByOrganizationId(organizationId);
            if (adminUserIds.isEmpty()) {
                return;
            }
            notificationHelper.notifyAllLocalized(
                    adminUserIds,
                    "NOTIFICATION_CREDIT_EXPIRED",
                    "NOTIFICATION_CREDIT",
                    organizationId,
                    NotificationScopeType.ORGANIZATION,
                    organizationId,
                    "/organizations/" + organizationId + "/settings/notification-credits",
                    null,
                    (userId, locale) -> new NotificationHelper.LocalizedMessage(
                            messageSource.getMessage(
                                    "notification.credit.expired.title", null,
                                    "通知クレジットが失効しました", locale),
                            messageSource.getMessage(
                                    "notification.credit.expired.body",
                                    new Object[]{expiredCredits},
                                    expiredCredits + "通のクレジットが有効期限切れにより失効しました。", locale))
            );
        } catch (Exception e) {
            log.error("クレジット失効アラート送信失敗: organizationId={}", organizationId, e);
        }
    }
}
