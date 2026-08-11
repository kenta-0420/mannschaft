package com.mannschaft.app.notification.credit.batch;

import com.mannschaft.app.admin.batch.BatchEndpoint;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.credit.entity.OrganizationNotificationBalanceEntity;
import com.mannschaft.app.notification.credit.repository.OrganizationNotificationBalanceRepository;
import com.mannschaft.app.notification.service.NotificationHelper;
import com.mannschaft.app.role.repository.UserRoleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/**
 * F09.13 通知クレジット月次リセットバッチ。
 *
 * <p>毎月1日 AM 2:00（JST）に以下を実行する:</p>
 * <ol>
 *   <li>猶予期間中の負債（{@code grace_period_debt}）を {@code credit_balance} から相殺</li>
 *   <li>相殺後に {@code credit_balance < 0} の組織へ ADMIN アラート（非同期）</li>
 *   <li>無料枠カウンタ・猶予期間フィールドをリセット</li>
 * </ol>
 *
 * <p>ShedLock により複数インスタンス起動時の二重実行を防ぐ。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationCreditMonthlyResetBatch {

    /** 1 ページあたりの抽出件数。 */
    static final int PAGE_SIZE = 500;

    /** 全件走査の暴走を防ぐ最大ページ数（500 件 × 200 ページ = 10 万件／回）。 */
    static final int MAX_PAGES = 200;

    private final OrganizationNotificationBalanceRepository balanceRepository;
    private final NotificationHelper notificationHelper;
    private final UserRoleRepository userRoleRepository;

    /**
     * 月次リセットバッチを実行する（毎月1日 AM 2:00 JST）。
     */
    @BatchEndpoint(name = "notification-credit-monthly-reset", description = "通知クレジットの無料枠と猶予負債を毎月 1 日 02:00 にリセットする")
    @Scheduled(cron = "0 0 2 1 * *", zone = "Asia/Tokyo")
    @SchedulerLock(
            name = "notificationCreditMonthlyReset",
            lockAtLeastFor = "PT10M",
            lockAtMostFor = "PT30M")
    @Transactional
    public void runBatch() {
        LocalDate today = LocalDate.now();
        LocalDate firstOfMonth = today.withDayOfMonth(1);
        log.info("通知クレジット月次リセットバッチ開始: month={}", firstOfMonth);

        int processedCount = 0;
        int negativeBalanceCount = 0;

        // 絞り込み条件の無い全件走査。monthlyReset() 実行後も対象母集合は縮まないため、
        // ページ0固定のドレインは無限ループになる。id 昇順キーセットページングでカーソルを
        // 必ず前進させる。
        long cursor = 0L;
        for (int page = 0; page < MAX_PAGES; page++) {
            List<OrganizationNotificationBalanceEntity> batch =
                    balanceRepository.findAllAfterId(cursor, PageRequest.of(0, PAGE_SIZE));
            if (batch.isEmpty()) {
                break;
            }

            for (OrganizationNotificationBalanceEntity balance : batch) {
                try {
                    // 猶予期間負債を相殺し、負の残高をチェック
                    boolean hadDebt = balance.getGracePeriodDebt() > 0;
                    balance.monthlyReset(firstOfMonth);
                    balanceRepository.save(balance);
                    processedCount++;

                    // 相殺後に残高がマイナスの組織へADMINアラート
                    if (hadDebt && balance.getCreditBalance() < 0) {
                        negativeBalanceCount++;
                        sendNegativeBalanceAlertAsync(balance.getOrganizationId(), balance.getCreditBalance());
                    }
                } catch (Exception e) {
                    log.error("月次リセット処理失敗: organizationId={}, error={}",
                            balance.getOrganizationId(), e.getMessage(), e);
                }
            }

            cursor = batch.get(batch.size() - 1).getId();
            if (batch.size() < PAGE_SIZE) {
                break;
            }
            if (page == MAX_PAGES - 1) {
                log.warn("通知クレジット月次リセットバッチ: MAX_PAGES={} に到達したため打ち切り", MAX_PAGES);
            }
        }

        log.info("通知クレジット月次リセットバッチ完了: 処理={}, 残高マイナス通知={}",
                processedCount, negativeBalanceCount);
    }

    /**
     * 残高マイナス警告をADMINへ非同期送信する。
     *
     * @param organizationId 組織ID
     * @param creditBalance  現在のクレジット残高（負の値）
     */
    @Async
    protected void sendNegativeBalanceAlertAsync(Long organizationId, Long creditBalance) {
        try {
            List<Long> adminUserIds = userRoleRepository.findAdminUserIdsByOrganizationId(organizationId);
            if (adminUserIds.isEmpty()) {
                return;
            }
            notificationHelper.notifyAll(
                    adminUserIds,
                    "NOTIFICATION_CREDIT_NEGATIVE",
                    "通知クレジット残高がマイナスです",
                    "猶予期間中の超過分が相殺された結果、クレジット残高がマイナスになりました。"
                            + "残高: " + creditBalance + "通。クレジットを購入してください。",
                    "NOTIFICATION_CREDIT",
                    organizationId,
                    NotificationScopeType.ORGANIZATION,
                    organizationId,
                    "/organizations/" + organizationId + "/settings/notification-credits",
                    null
            );
            log.info("残高マイナスアラート送信: organizationId={}, balance={}", organizationId, creditBalance);
        } catch (Exception e) {
            log.error("残高マイナスアラート送信失敗: organizationId={}", organizationId, e);
        }
    }
}
