package com.mannschaft.app.gamification.event;

import com.mannschaft.app.auth.event.LoginSuccessEvent;
import com.mannschaft.app.gamification.ActionType;
import com.mannschaft.app.gamification.service.GamificationPointService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * ゲーミフィケーション・ポイント付与イベントリスナー。
 * 各ドメインイベントを受信し、ゲーミフィケーションポイントを付与する。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GamificationPointListener {

    private final GamificationPointService gamificationPointService;

    /**
     * TODO: TimelinePostCreatedEventを購読予定。
     * タイムライン投稿時にACTION_TYPE=TIMELINE_POSTのポイントを付与する。
     *
     * <pre>
     * {@code
     * @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
     * @Async("event-pool")
     * public void handleTimelinePostCreated(TimelinePostCreatedEvent event) {
     *     gamificationPointService.addPoint(
     *         event.getUserId(),
     *         event.getScopeType(),
     *         event.getScopeId(),
     *         ActionType.TIMELINE_POST,
     *         "TIMELINE_POST",
     *         event.getPostId()
     *     );
     * }
     * }
     * </pre>
     */

    /**
     * ログイン成功イベントを受信し、デイリーログインポイントを付与する。
     *
     * <p>スコープ解決について: LoginSuccessEventはスコープ情報（scopeType/scopeId）を保持していないため、
     * 現時点ではスコープを特定できない。後続実装でスコープ解決ロジックを追加する予定。</p>
     *
     * @param event ログイン成功イベント
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async("event-pool")
    public void handleDailyLogin(LoginSuccessEvent event) {
        log.debug("デイリーログインイベント受信: userId={}", event.getUserId());

        // TODO: スコープ解決ロジックは後続実装。
        // LoginSuccessEventはscopeType/scopeIdを持たないため、
        // ユーザーの所属チームを取得してスコープを特定する必要がある。
        // 例: userTeamRepository.findActiveTeamsByUserId(event.getUserId()) でスコープ一覧を取得し、
        // 各スコープに対して gamificationPointService.addPoint() を呼び出す。
        log.info("デイリーログインポイント付与: スコープ解決ロジック未実装のためスキップ: userId={}", event.getUserId());
    }
}
