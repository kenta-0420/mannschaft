package com.mannschaft.app.inbox.event;

import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.auth.event.UserAnonymizedEvent;
import com.mannschaft.app.inbox.repository.InboxItemStateRepository;
import com.mannschaft.app.inbox.repository.InboxLabelLinkRepository;
import com.mannschaft.app.inbox.repository.NotificationLabelRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * F04.11 統合通知インボックス：ユーザー退会（匿名化）イベントリスナー。
 *
 * <p>インボックスの triage 状態・ラベル・リンクは PII を含まない個人設定/状態であり、
 * CLAUDE.md §13.12 の「弱匿名化区分（再設定で復旧可能）」に該当するため、退会受付直後に
 * <b>即時物理削除</b>する（設計書: 04_security_operations.md §3）。</p>
 *
 * <p>手本は {@code FavoriteAnonymizationEventListener}。三重防御:
 * <ul>
 *   <li>{@code @Async("event-pool")} — 呼び出し元 TX とスレッド分離</li>
 *   <li>{@code @TransactionalEventListener(AFTER_COMMIT)} — 退会本体のコミット成立後のみ実行</li>
 *   <li>{@code @Transactional(REQUIRES_NEW)} — 独立した新規 TX で実行</li>
 * </ul></p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InboxAnonymizationEventListener {

    private final InboxItemStateRepository itemStateRepository;
    private final NotificationLabelRepository labelRepository;
    private final InboxLabelLinkRepository labelLinkRepository;

    /**
     * ユーザー退会（匿名化）完了時にインボックスの 3 表を物理削除する。
     *
     * @param event 匿名化完了イベント
     */
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "止めると退会済み利用者の受信箱に個人情報が残存し、退会済みなのに PII が残るという不整合になる")
    @Async("event-pool")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleUserAnonymized(UserAnonymizedEvent event) {
        Long userId = event.getUserId();
        try {
            labelLinkRepository.deleteAllByUserId(userId);
            itemStateRepository.deleteAllByUserId(userId);
            labelRepository.deleteAllByUserId(userId);
            log.info("ユーザー退会: 通知インボックスデータ削除完了: userId={}", userId);
        } catch (Exception e) {
            // 退会本体トランザクション（AFTER_COMMIT 済み・別 TX）には伝播させず、
            // 失敗を握り潰さずログに残す。インボックスは再設定可能な弱匿名化区分（CLAUDE.md §13.12）
            // のため退会フロー自体は止めない方針。
            // TODO(F04.11 Phase 3+): 失敗 userId を再処理キュー/メトリクスに積み、取りこぼしゼロを担保する。
            log.warn("ユーザー退会: 通知インボックスデータ削除失敗: userId={}, error={}",
                    userId, e.getMessage(), e);
        }
    }
}
