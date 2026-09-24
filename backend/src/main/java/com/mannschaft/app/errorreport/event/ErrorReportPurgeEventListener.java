package com.mannschaft.app.errorreport.event;

import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.errorreport.repository.ErrorReportOccurrenceRepository;
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
 * errorreport ドメインの {@code error_report_occurrences} 行に対して
 * {@code ip_address} / {@code user_agent} / {@code user_id} を NULL 化（匿名化）する。
 *
 * <p>F12.5 Phase 2-F で {@link com.mannschaft.app.gdpr.service.AccountPurgeService} が
 * 直接呼び出していた {@code anonymizeByUserId} と同等の挙動を維持する。
 * 既存越境 DML はリスナー安定稼働確認後の Phase C で撤去予定（親設計書 §4 Phase C-6）。</p>
 *
 * <p><b>三重防御パターン:</b>
 * <ul>
 *   <li>{@code @Async("event-pool")} — 呼び出し元 TX とスレッド分離</li>
 *   <li>{@code @TransactionalEventListener(AFTER_COMMIT)} — gdpr 側コミット成立後に実行</li>
 *   <li>{@code @Transactional(REQUIRES_NEW)} — 独立した新規 TX</li>
 * </ul>
 * </p>
 *
 * <p><b>匿名化対象カラム（F12.5 Phase 2-F 仕様維持）:</b>
 * <ul>
 *   <li>{@code ip_address} — アプリ層で明示 NULL 化（FK 制約なし）</li>
 *   <li>{@code user_agent} — アプリ層で明示 NULL 化（FK 制約なし）</li>
 *   <li>{@code user_id} — リポジトリの {@code anonymizeByUserId} JPQL で NULL 化
 *       （ON DELETE SET NULL の FK 制約はあるが、user 本体削除を待たずに先に匿名化する）</li>
 * </ul>
 * </p>
 *
 * <p>設計根拠: {@code docs/architecture/account_purge_cross_domain_refactor.md}
 * §4 Phase B-6 / PR #837（Phase B-1 role）同型</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ErrorReportPurgeEventListener {

    private final ErrorReportOccurrenceRepository errorReportOccurrenceRepository;
    private final AccountPurgeCompletionStatusRepository completionStatusRepository;

    /**
     * {@link AccountPurgedEvent} を購読し、対象ユーザーに紐づく
     * {@code error_report_occurrences} 行の PII 系カラムを NULL 化する。
     *
     * <p>例外発生時は WARN ログのみで伝播させない（GDPR 30 日タイムリミットを優先し、
     * 他リスナーの処理を妨げない）。失敗分は夜次補正バッチ（Phase D）で再処理する運用とする。</p>
     */
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "退会アカウントの消去イベントを購読しエラーレポートの個人データを消す。止めると GDPR 第17条の消去期限を破り、イベントは再生されない")
    @Async("purge-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void on(AccountPurgedEvent event) {
        Long userId = event.getUserId();
        boolean success = false;
        try {
            int anonymized = errorReportOccurrenceRepository.anonymizeByUserId(userId);
            log.info("ユーザー退会 errorreport purge 完了: userId={}, anonymizedOccurrences={}",
                    userId, anonymized);
            success = true;
        } catch (Exception e) {
            log.warn("ユーザー退会 errorreport purge: 匿名化失敗 userId={}, error={}",
                    userId, e.getMessage(), e);
        }

        // Phase D-8: 処理完了を completion_status に記録
        if (success) {
            completionStatusRepository.findByUserIdAndDomainName(userId, "errorreport")
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
            errorReportOccurrenceRepository.anonymizeByUserId(userId);
            return true;
        } catch (Exception e) {
            log.warn("errorreport purge retry: 匿名化失敗 userId={}", userId, e);
            return false;
        }
    }
}
