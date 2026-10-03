package com.mannschaft.app.chart.event;

import com.mannschaft.app.chart.repository.ChartRecordRepository;
import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.gdpr.event.AccountPurgedEvent;
import com.mannschaft.app.gdpr.repository.AccountPurgeCompletionStatusRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.LocalDateTime;

/**
 * 30 日後物理削除（{@link AccountPurgedEvent}）を購読し、
 * chart ドメインの {@code chart_records} 行に対して
 * {@code customer_user_id} を NULL 化（匿名化）する。
 *
 * <p>{@link com.mannschaft.app.gdpr.service.AccountPurgeService} が
 * 直接呼び出していた {@code anonymizeCustomerUserId} と同等の挙動を維持する。
 * 既存越境 DML はリスナー安定稼働確認後の Phase C で撤去予定（親設計書 §4 Phase C-4）。</p>
 *
 * <p><b>三重防御パターン:</b>
 * <ul>
 *   <li>{@code @Async("event-pool")} — 呼び出し元 TX とスレッド分離</li>
 *   <li>{@code @TransactionalEventListener(AFTER_COMMIT)} — gdpr 側コミット成立後に実行</li>
 *   <li>{@code @Transactional(REQUIRES_NEW)} — 独立した新規 TX</li>
 * </ul>
 * </p>
 *
 * <p><b>匿名化対象カラム:</b>
 * <ul>
 *   <li>{@code customer_user_id} — リポジトリの {@code anonymizeCustomerUserId} JPQL で NULL 化
 *       （{@code ON DELETE RESTRICT} の FK 制約があるため、user 本体削除より前に必ず NULL 化する）</li>
 * </ul>
 * </p>
 *
 * <p>設計根拠: {@code docs/architecture/account_purge_cross_domain_refactor.md}
 * §4 Phase B-4 / PR #837（Phase B-1 role）同型</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChartPurgeEventListener {

    private final ChartRecordRepository chartRecordRepository;
    private final AccountPurgeCompletionStatusRepository completionStatusRepository;

    /**
     * {@link AccountPurgedEvent} を購読し、対象ユーザーが顧客として紐づく
     * {@code chart_records} 行の {@code customer_user_id} を NULL 化する。
     *
     * <p>例外発生時は WARN ログのみで伝播させない（GDPR 30 日タイムリミットを優先し、
     * 他リスナーの処理を妨げない）。失敗分は夜次補正バッチ（Phase D）で再処理する運用とする。</p>
     */
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "退会アカウントの消去イベントを購読しチャートの個人データを消す。止めると GDPR 第17条の消去期限を破り、イベントは再生されない")
    @Async("purge-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void on(AccountPurgedEvent event) {
        Long userId = event.getUserId();
        boolean success = false;
        try {
            int anonymized = chartRecordRepository.anonymizeCustomerUserId(userId);
            log.info("ユーザー退会 chart purge 完了: userId={}, anonymizedChartRecords={}",
                    userId, anonymized);
            success = true;
        } catch (Exception e) {
            log.warn("ユーザー退会 chart purge: 匿名化失敗 userId={}, error={}",
                    userId, e.getMessage(), e);
        }

        // Phase D-8: 処理完了を completion_status に記録
        if (success) {
            completionStatusRepository.findByUserIdAndDomainName(userId, "chart")
                    .ifPresent(entity -> {
                        entity.setStatus("SUCCESS");
                        entity.setCompletedAt(LocalDateTime.now());
                        completionStatusRepository.save(entity);
                    });
        }
    }

    /**
     * 管理者からの手動 retry 用。{@link #on(AccountPurgedEvent)} と同じドメイン操作を実行するが、
     * {@code completionStatusRepository} の更新は {@code GdprPurgeRetryService} が担う。
     *
     * @param userId retry 対象ユーザー ID
     * @return true=成功、false=失敗
     */
    @Transactional
    public boolean retryPurge(Long userId) {
        try {
            chartRecordRepository.anonymizeCustomerUserId(userId);
            return true;
        } catch (Exception e) {
            log.warn("chart purge retry: 匿名化失敗 userId={}", userId, e);
            return false;
        }
    }
}
