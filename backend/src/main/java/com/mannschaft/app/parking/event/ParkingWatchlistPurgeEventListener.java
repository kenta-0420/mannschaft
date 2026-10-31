package com.mannschaft.app.parking.event;

import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.gdpr.event.AccountPurgedEvent;
import com.mannschaft.app.gdpr.service.AccountPurgeCompletionService;
import com.mannschaft.app.parking.repository.ParkingWatchlistRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 本人の駐車区画ウォッチリストを30日後に強消去する。区画契約・申請等の業務記録は対象外。
 *
 * <p>CMP-260822-1243: 退会30日後の強匿名化で、本人だけが使う {@code parking_watchlist} を所有列 {@code user_id} で
 * 一括物理削除する。他人の行は残す。所有ドメイン内の削除を1つの新規TXで行い、途中のSQL失敗は
 * TX全体をロールバックして例外を伝播させる（完了記録は残らず PENDING のまま手動 retry 対象になる）。</p>
 *
 * <p>設計根拠: {@code docs/architecture/account_purge_cross_domain_refactor.md} §CMP-260822-1243</p>
 */
@Component
@RequiredArgsConstructor
public class ParkingWatchlistPurgeEventListener {

    private final ParkingWatchlistRepository parkingWatchlistRepository;
    private final AccountPurgeCompletionService completionService;

    /** 30日後の強匿名化。所有データの削除コミット後にのみ完了を記録する。 */
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "完全削除済み利用者の駐車区画ウォッチリストを消去する。停止すると個人設定が残留し、消去イベントは再生されない")
    @Async("purge-pool")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onAccountPurged(AccountPurgedEvent event) {
        Long userId = event.getUserId();
        purgeOwnedRows(userId);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                completionService.markDomainSuccess(userId, "parking");
            }
        });
    }

    /** 手動再試行。呼出元はこの新規TXのコミット成立後に完了状態を更新する。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean retryPurge(Long userId) {
        purgeOwnedRows(userId);
        return true;
    }

    /** 同じ所有domain内の全削除を一つのTXで実行し、途中失敗を伝播させる。対象0件でも成功とする。 */
    private void purgeOwnedRows(Long userId) {
        parkingWatchlistRepository.deleteAllOwnedByUserId(userId);
    }
}
