package com.mannschaft.app.gamification.event;

import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.gamification.ActionType;
import com.mannschaft.app.gamification.service.GamificationPointService;
import com.mannschaft.app.timeline.event.TimelinePostCreatedEvent;
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
     * タイムライン投稿作成イベントを受信し、TIMELINE_POSTポイントを付与する。
     * スコープ（TEAM / ORGANIZATION）にポイントルールが設定されている場合のみ付与する。
     *
     * @param event タイムライン投稿作成イベント
     */
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "上流のタイムライン投稿は CORE であり、ゲーミフィケーションのゲートでは閉じない。よって閉栓中もイベントは飛んでくる。落とすと投稿に対するポイント加算が恒久的に欠落し、再生もバックフィルも無い。付与は内部処理のみで外部送信を伴わない")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async("event-pool")
    public void handleTimelinePostCreated(TimelinePostCreatedEvent event) {
        log.debug("タイムライン投稿イベント受信: postId={}, userId={}, scopeType={}",
                event.getPostId(), event.getUserId(), event.getScopeType());
        gamificationPointService.addPoint(
                event.getUserId(),
                event.getScopeType(),
                event.getScopeId(),
                ActionType.TIMELINE_POST,
                "TIMELINE_POST",
                event.getPostId()
        );
    }

}
