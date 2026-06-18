package com.mannschaft.app.timeline.event;

import com.mannschaft.app.gdpr.event.AccountPurgedEvent;
import com.mannschaft.app.timeline.repository.TimelineBookmarkRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

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
 * 例外は WARN ログのみで伝播させない（他ドメインリスナーの処理を妨げない／GDPR タイムリミットを優先）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TimelineBookmarkAnonymizationEventListener {

    private final TimelineBookmarkRepository timelineBookmarkRepository;

    /**
     * 退会30日後の物理削除（{@link AccountPurgedEvent}）を購読し、
     * ブックマーク（お気に入り＝個人設定・復元価値あり）を削除する。
     *
     * @param event アカウント物理削除完了イベント
     */
    @Async("purge-pool")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onAccountPurged(AccountPurgedEvent event) {
        Long userId = event.getUserId();
        try {
            int deleted = timelineBookmarkRepository.deleteByUserId(userId);
            log.info("ユーザー退会 timeline purge 完了: ブックマーク削除: userId={}, deleted={}",
                    userId, deleted);
        } catch (Exception e) {
            log.warn("ユーザー退会 timeline purge: ブックマーク削除失敗: userId={}, error={}",
                    userId, e.getMessage(), e);
        }
    }
}
