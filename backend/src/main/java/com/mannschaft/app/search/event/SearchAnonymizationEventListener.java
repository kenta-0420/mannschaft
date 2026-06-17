package com.mannschaft.app.search.event;

import com.mannschaft.app.auth.event.UserAnonymizedEvent;
import com.mannschaft.app.gdpr.event.AccountPurgedEvent;
import com.mannschaft.app.search.repository.SearchHistoryRepository;
import com.mannschaft.app.search.repository.SearchSavedQueryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * search ドメインの退会データ削除リスナー（クロスドメインFK撤廃キャンペーン 第二陣B）。
 *
 * <p>users を親とする ON DELETE CASCADE のクロスドメインFK
 * （{@code fk_search_histories_user} / {@code fk_search_saved_queries_user}）を
 * V97.001 で撤廃するにあたり、退会フローでリスナーが先行削除することで
 * CASCADE を冗長化する（第一陣 notification と同じ論法）。</p>
 *
 * <p><b>二層削除モデル（CLAUDE.md「PII 消去のタイミング §13.12」）:</b>
 * <ul>
 *   <li><b>検索履歴（search_histories）= 退会時即時削除</b> —
 *       検索クエリそのものは「ユーザーが何を探したか」を表す PII であり、
 *       再設定で復旧する性質のものでもない。漏洩リスクを最小化するため
 *       {@link UserAnonymizedEvent}（退会受付直後・即時消去）を購読して削除する。</li>
 *   <li><b>保存済みクエリ（search_saved_queries）= 30日後の物理削除時削除</b> —
 *       保存済みクエリは「ユーザーが意図的に保存した検索条件」＝個人設定であり、
 *       退会撤回時に復元価値がある。GDPR Art.17 の30日撤回ウィンドウを保持するため
 *       {@link AccountPurgedEvent}（30日後の物理削除完了）を購読して削除する。</li>
 * </ul>
 * </p>
 *
 * <p><b>三重防御パターン（過去の ApplicationContext 全滅事故の再発防止）:</b>
 * <ul>
 *   <li>{@code @Async(...)} — 呼び出し元 TX とスレッド分離（即時=event-pool / 30日=purge-pool）</li>
 *   <li>{@code @TransactionalEventListener(AFTER_COMMIT)} — 呼出元コミット成立後のみ実行</li>
 *   <li>{@code @Transactional(REQUIRES_NEW)} — 独立した新規 TX。
 *       素の {@code REQUIRED} は AFTER_COMMIT では起動時バリデーションで弾かれるか
 *       silently 破棄されるため必須。</li>
 * </ul>
 * </p>
 *
 * <p>例外は WARN ログのみで伝播させない（他ドメインリスナーの処理を妨げない／
 * GDPR タイムリミットを優先する）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SearchAnonymizationEventListener {

    private final SearchHistoryRepository searchHistoryRepository;
    private final SearchSavedQueryRepository searchSavedQueryRepository;

    /**
     * 退会即時匿名化（{@link UserAnonymizedEvent}）を購読し、検索履歴（PII）を即時削除する。
     *
     * @param event 退会即時匿名化イベント
     */
    @Async("event-pool")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onUserAnonymized(UserAnonymizedEvent event) {
        Long userId = event.getUserId();
        try {
            searchHistoryRepository.deleteByUserId(userId);
            log.info("ユーザー退会: 検索履歴を即時削除完了: userId={}", userId);
        } catch (Exception e) {
            log.warn("ユーザー退会: 検索履歴の即時削除失敗: userId={}, error={}",
                    userId, e.getMessage(), e);
        }
    }

    /**
     * 退会30日後の物理削除（{@link AccountPurgedEvent}）を購読し、
     * 保存済みクエリ（個人設定・復元価値）を削除する。
     *
     * @param event アカウント物理削除完了イベント
     */
    @Async("purge-pool")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onAccountPurged(AccountPurgedEvent event) {
        Long userId = event.getUserId();
        try {
            searchSavedQueryRepository.deleteByUserId(userId);
            log.info("ユーザー退会 search purge 完了: 保存済みクエリ削除: userId={}", userId);
        } catch (Exception e) {
            log.warn("ユーザー退会 search purge: 保存済みクエリ削除失敗: userId={}, error={}",
                    userId, e.getMessage(), e);
        }
    }
}
