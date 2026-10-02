package com.mannschaft.app.notification.credit.service;

import com.mannschaft.app.notification.credit.entity.OrganizationNotificationBalanceEntity;
import com.mannschaft.app.notification.credit.repository.OrganizationNotificationBalanceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

/**
 * 通知クレジット月次リセットバッチ用の 1 件リセット REQUIRES_NEW 実行 Bean（CMP-035）。
 *
 * <p>{@code NotificationCreditMonthlyResetBatch#runBatch()} からループで呼ばれる。
 * バッチ失敗時のリトライ安全性を確保するため、1 件のリセット = 1 独立トランザクションとする必要があり、
 * 独立した Bean に切り出し {@link Propagation#REQUIRES_NEW} を付与する
 * （同一 Bean 内の自己呼び出しではプロキシを経由せず伝播設定が効かないため）。
 */
@Component
@RequiredArgsConstructor
public class NotificationCreditResetRunner {

    private final OrganizationNotificationBalanceRepository balanceRepository;

    /**
     * 指定組織のクレジット残高を独立トランザクションで月次リセットする。
     *
     * @param balanceId    残高エンティティ ID
     * @param firstOfMonth 今月1日の日付
     * @return リセット後にマイナス残高となった場合はアラート要否を含む結果、対象が既に存在しない場合は {@code null}
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public NotificationCreditResetOutcome resetOne(Long balanceId, LocalDate firstOfMonth) {
        OrganizationNotificationBalanceEntity balance = balanceRepository.findById(balanceId).orElse(null);
        if (balance == null) {
            return null;
        }

        boolean hadDebt = balance.getGracePeriodDebt() > 0;
        balance.monthlyReset(firstOfMonth);
        balanceRepository.save(balance);

        boolean shouldAlert = hadDebt && balance.getCreditBalance() < 0;
        return new NotificationCreditResetOutcome(shouldAlert, balance.getOrganizationId(), balance.getCreditBalance());
    }
}
