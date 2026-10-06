package com.mannschaft.app.favorite.event;

import com.mannschaft.app.auth.event.UserAnonymizedEvent;
import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.favorite.repository.UserFavoriteRepository;
import com.mannschaft.app.gdpr.event.AccountPurgedEvent;
import com.mannschaft.app.gdpr.service.AccountPurgeCompletionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * ユーザー退会（匿名化）イベントを受け取り、お気に入りデータを削除するリスナー。
 *
 * <p>退会ユーザーの個人データ消去（GDPR対応）の一環として、
 * {@link UserAnonymizedEvent} の発行をトリガーに {@code user_favorites} を物理削除する。
 * クロスドメインFK非依存のイベント駆動設計（CLAUDE.md 原則5）。</p>
 *
 * <p><b>Phase W-A（2026-05-18）改修:</b>
 * 他の {@code *AnonymizationEventListener}（{@code AuthAnonymizationEventListener} 等）
 * と TX 伝播設計を揃えた。三重防御:
 * <ul>
 *   <li>{@code @Async("event-pool")} — 呼び出し元 TX とスレッド分離</li>
 *   <li>{@code @TransactionalEventListener(AFTER_COMMIT)} — 呼び出し元のコミット成立後のみ実行</li>
 *   <li>{@code @Transactional(REQUIRES_NEW)} — 独立した新規 TX で実行（呼出元 TX を巻き込まない）</li>
 * </ul>
 * これにより、退会本体 TX のロールバック時に Favorite 削除のみが先行コミットされる
 * 競合を防ぐ。設計根拠: {@code docs/architecture/withdrawal_flow_immediate_anonymization_fix.md}
 * §13.7（マスター御裁可 2026-05-18: A 採用）。</p>
 *
 * <p><b>注:</b> Phase W-A 段階では {@code event-pool} を継続利用する。
 * Phase W-C で {@code withdrawal-pool} 専用プールへの切替えを行う予定。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FavoriteAnonymizationEventListener {

    private final AccountPurgeCompletionService completionService;

    private final UserFavoriteRepository userFavoriteRepository;

    /**
     * ユーザー退会（匿名化）完了時にお気に入りを削除する。
     *
     * @param event 匿名化完了イベント
     */
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "止めると退会済み利用者のお気に入り情報に個人情報が残存し、退会済みなのに PII が残るという不整合になる")
    @Async("event-pool")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleUserAnonymized(UserAnonymizedEvent event) {
        Long userId = event.getUserId();
        try {
            userFavoriteRepository.deleteAllByUserId(userId);
            log.info("ユーザー退会: お気に入り削除完了: userId={}", userId);
        } catch (Exception e) {
            log.warn("ユーザー退会: お気に入り削除失敗: userId={}, error={}",
                    userId, e.getMessage(), e);
        }
    }

    /** 30日後の強匿名化。所有データの削除コミット後にのみ完了を記録する。 */
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "完全削除済み利用者の個人設定を消去する。停止すると設定が残留し、消去イベントは再生されない")
    @Async("purge-pool")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onAccountPurged(AccountPurgedEvent event) {
        Long userId = event.getUserId();
        purgeSettings(userId);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                completionService.markDomainSuccess(userId, "favorite");
            }
        });
    }

    /** 手動再試行。呼出元はこの新規TXのコミット成立後に完了状態を更新する。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean retryPurge(Long userId) {
        purgeSettings(userId);
        return true;
    }

    /** 同じ所有domain内の全削除を一つのTXで実行し、途中失敗を伝播させる。 */
    private void purgeSettings(Long userId) {
        userFavoriteRepository.deleteAllByUserId(userId);
    }
}
