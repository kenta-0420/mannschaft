package com.mannschaft.app.proxy.event;

import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.gdpr.event.AccountPurgedEvent;
import com.mannschaft.app.gdpr.repository.AccountPurgeCompletionStatusRepository;
import com.mannschaft.app.proxy.repository.ProxyInputConsentRepository;
import com.mannschaft.app.proxy.repository.ProxyInputRecordRepository;
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
 * proxy ドメインの代理入力関連データを清掃する。
 *
 * <p>F14.1 Phase 13-γ で導入された代理入力（家族・支援者による本人代理での
 * データ入力）機能のデータ。本人退会時に本人（subject）として関与する
 * レコードを以下のポリシーで清掃する:</p>
 *
 * <p><b>2 操作・混在型（物理削除 + 論理削除）:</b>
 * <ul>
 *   <li>{@code proxy_input_records} — <b>物理削除</b>
 *       （代理入力の実行ログ。本人特定情報そのものを含むため GDPR 削除権により消去）</li>
 *   <li>{@code proxy_input_consents} — <b>論理削除</b>
 *       （代理同意書。監査証跡として記録自体は保持する必要があるため
 *       {@code deleted_at} へ現在時刻を設定するに留める）</li>
 * </ul>
 * </p>
 *
 * <p>F12.5 Phase 2-F で {@link com.mannschaft.app.gdpr.service.AccountPurgeService} が
 * 直接呼び出していた {@code proxyInputRecordRepository.deleteAllBySubjectUserId} および
 * {@code proxyInputConsentRepository.logicalDeleteAllBySubjectUserId} と同等の挙動を維持する。
 * 既存越境 DML はリスナー安定稼働確認後の Phase C で撤去予定（親設計書 §4 Phase C-5）。</p>
 *
 * <p><b>三重防御パターン:</b>
 * <ul>
 *   <li>{@code @Async("event-pool")} — 呼び出し元 TX とスレッド分離</li>
 *   <li>{@code @TransactionalEventListener(AFTER_COMMIT)} — gdpr 側コミット成立後に実行</li>
 *   <li>{@code @Transactional(REQUIRES_NEW)} — 独立した新規 TX</li>
 * </ul>
 * </p>
 *
 * <p><b>独立 try-catch による継続性保証:</b>
 * 2 操作はそれぞれ独立した try-catch で囲み、片方が失敗してももう片方の処理を
 * 継続する（GDPR 30 日タイムリミットを優先）。失敗分は WARN ログを残し、
 * 夜次補正バッチ（Phase D）で再処理する運用とする。</p>
 *
 * <p>設計根拠: {@code docs/architecture/account_purge_cross_domain_refactor.md}
 * §4 Phase B-5 / PR #837 (Phase B-1 role) 同型 / F14.1 Phase 13-γ 由来</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProxyPurgeEventListener {

    private final ProxyInputRecordRepository proxyInputRecordRepository;
    private final ProxyInputConsentRepository proxyInputConsentRepository;
    private final AccountPurgeCompletionStatusRepository completionStatusRepository;

    /**
     * {@link AccountPurgedEvent} を購読し、対象ユーザーが本人（subject）として
     * 関与する代理入力レコードを清掃する。
     *
     * <p>各操作は独立 try-catch で囲み、片方の失敗が他方の継続を妨げない。
     * 例外は伝播させず WARN ログのみ（GDPR 30 日タイムリミット優先）。</p>
     */
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "止めると退会確定者が本人として関与する代理入力記録と同意が消去されず、GDPR 第17条の消去期限を直接破る")
    @Async("purge-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void on(AccountPurgedEvent event) {
        Long userId = event.getUserId();
        boolean recordsOk = false;
        boolean consentsOk = false;

        try {
            proxyInputRecordRepository.deleteAllBySubjectUserId(userId);
            recordsOk = true;
        } catch (Exception e) {
            log.warn("ユーザー退会 proxy purge: proxy_input_records 物理削除失敗 userId={}, error={}",
                    userId, e.getMessage(), e);
        }

        try {
            proxyInputConsentRepository.logicalDeleteAllBySubjectUserId(userId);
            consentsOk = true;
        } catch (Exception e) {
            log.warn("ユーザー退会 proxy purge: proxy_input_consents 論理削除失敗 userId={}, error={}",
                    userId, e.getMessage(), e);
        }

        log.info("ユーザー退会 proxy purge 完了: userId={}, recordsDeleted={}, consentsLogicalDeleted={}",
                userId, recordsOk, consentsOk);

        // Phase D-8: 処理完了を completion_status に記録（両操作とも成功した場合のみ SUCCESS とする）
        if (recordsOk && consentsOk) {
            completionStatusRepository.findByUserIdAndDomainName(userId, "proxy")
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
     * @return true=全操作成功、false=1 件以上失敗
     */
    @Transactional
    public boolean retryPurge(Long userId) {
        boolean recordsOk = false;
        boolean consentsOk = false;

        try {
            proxyInputRecordRepository.deleteAllBySubjectUserId(userId);
            recordsOk = true;
        } catch (Exception e) {
            log.warn("proxy purge retry: proxy_input_records 物理削除失敗 userId={}", userId, e);
        }

        try {
            proxyInputConsentRepository.logicalDeleteAllBySubjectUserId(userId);
            consentsOk = true;
        } catch (Exception e) {
            log.warn("proxy purge retry: proxy_input_consents 論理削除失敗 userId={}", userId, e);
        }

        return recordsOk && consentsOk;
    }
}
