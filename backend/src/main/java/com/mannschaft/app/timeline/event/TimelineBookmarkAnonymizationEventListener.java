package com.mannschaft.app.timeline.event;

import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.gdpr.event.AccountPurgedEvent;
import com.mannschaft.app.gdpr.service.AccountPurgeCompletionService;
import com.mannschaft.app.timeline.repository.TimelineBookmarkRepository;
import com.mannschaft.app.timeline.repository.UserMuteRepository;
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
 * timeline ドメインの退会データ削除リスナー（クロスドメインFK撤廃キャンペーン 第二陣E）。
 *
 * <p>users を親とする ON DELETE CASCADE のクロスドメインFK {@code fk_bookmarks_user}
 * （timeline_bookmarks.user_id → users CASCADE）を V100.001 で撤廃するにあたり、
 * 退会フローでリスナーが先行削除することで CASCADE を冗長化する
 * （第一陣 notification・第二陣 pointcard / search / actionmemo と同じ論法）。</p>
 *
 * <p><b>二層削除モデル（CLAUDE.md「PII 消去のタイミング §13.12」）での区分:</b>
 * タイムラインブックマーク（timeline_bookmarks）はユーザーが意図的に登録したお気に入り＝個人「設定」であり、
 * 退会撤回時に復元価値がある。よって即時ではなく、GDPR Art.17 の30日撤回ウィンドウを保持した
 * <b>退会30日後の物理削除時【削除】</b>として {@link AccountPurgedEvent}（30日後の物理削除完了）を
 * 購読して削除する。</p>
 *
 * <p><b>同一ドメイン内 FK は対象外:</b> {@code fk_bookmarks_post}
 * （timeline_post_id → timeline_posts ON DELETE CASCADE）は同一 timeline ドメイン内 CASCADE のため
 * V100.001 でも残す（CLAUDE.md §2 で許可）。ブックマーク行の削除は user_id 起点で行い、post は触らない。</p>
 *
 * <p><b>三重防御パターン（過去の ApplicationContext 全滅事故の再発防止）:</b>
 * <ul>
 *   <li>{@code @Async("purge-pool")} — 呼び出し元 TX とスレッド分離（30日後物理削除プール）</li>
 *   <li>{@code @TransactionalEventListener(AFTER_COMMIT)} — 呼出元コミット成立後のみ実行</li>
 *   <li>{@code @Transactional(REQUIRES_NEW)} — 独立した新規 TX。
 *       素の {@code REQUIRED} は AFTER_COMMIT では起動時バリデーションで弾かれるため必須。</li>
 * </ul>
 * 削除失敗は所有TXをロールバックさせPENDINGを維持し、コミット成立後だけ完了記録する。
 * 本人のミュート設定も同じTXで削除し、投稿本体・他所有者の設定は保持する。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TimelineBookmarkAnonymizationEventListener {

    private final UserMuteRepository userMuteRepository;
    private final AccountPurgeCompletionService completionService;

    private final TimelineBookmarkRepository timelineBookmarkRepository;
    private final com.mannschaft.app.timeline.repository.TimelineRanchTransportRepository ranchTransport;

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
                completionService.markDomainSuccess(userId, "timeline");
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
        timelineBookmarkRepository.deleteByUserId(userId);
        userMuteRepository.deleteByUserId(userId);
        ranchTransport.deleteForUser(userId);
    }
}
