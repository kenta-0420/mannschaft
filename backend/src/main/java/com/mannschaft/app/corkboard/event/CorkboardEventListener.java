package com.mannschaft.app.corkboard.event;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.HashMap;
import java.util.Map;

/**
 * F09.8 Phase A-3: {@link CorkboardEvent} を STOMP トピックへ配信する。
 *
 * <p>配信先トピック: {@code /topic/corkboard/{boardId}}<br>
 * 配信タイミング: トランザクションコミット後 ({@link TransactionPhase#AFTER_COMMIT})。<br>
 * これにより、ロールバック時にイベントが配信されることを防ぐ。</p>
 *
 * <p>個人ボードへの配信スキップは呼び出し元で {@code publishEvent} を発行しないことで担保する
 * （= 共有ボード操作時のみイベントを発行する設計）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CorkboardEventListener {

    private static final String TOPIC_FORMAT = "/topic/corkboard/%d";

    private final SimpMessagingTemplate messagingTemplate;

    /**
     * トランザクションコミット後にイベントを STOMP 配信する。
     *
     * @param event 配信対象イベント
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCorkboardEvent(CorkboardEvent event) {
        if (event == null || event.boardId() == null) {
            return;
        }
        try {
            String destination = String.format(TOPIC_FORMAT, event.boardId());
            Map<String, Object> payload = new HashMap<>();
            payload.put("eventType", event.eventType().name());
            payload.put("boardId", event.boardId());
            if (event.cardId() != null) {
                payload.put("cardId", event.cardId());
            }
            if (event.sectionId() != null) {
                payload.put("sectionId", event.sectionId());
            }
            // 件B: card / section ペイロードもフロントへ配信し、局所更新を可能にする。
            // 旧ファクトリ経由（payload なし）の場合は null のまま渡らないよう、
            // 値があるときのみマップへ載せる。Jackson が DTO を camelCase で JSON 化する。
            if (event.card() != null) {
                payload.put("card", event.card());
            }
            if (event.section() != null) {
                payload.put("section", event.section());
            }
            messagingTemplate.convertAndSend(destination, payload);
            log.debug("コルクボードイベント配信: dest={}, type={}, hasCard={}, hasSection={}",
                    destination, event.eventType(), event.card() != null, event.section() != null);
        } catch (Exception e) {
            // 配信失敗は WARN ログのみ（業務 TX には影響させない）
            log.warn("コルクボードイベント配信失敗: boardId={}, type={}, cause={}",
                    event.boardId(), event.eventType(), e.getClass().getSimpleName());
        }
    }
}
