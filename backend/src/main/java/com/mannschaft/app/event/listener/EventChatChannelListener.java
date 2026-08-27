package com.mannschaft.app.event.listener;

import com.mannschaft.app.chat.service.EventChatChannelService;
import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.event.EventStatus;
import com.mannschaft.app.event.event.EventCreatedEvent;
import com.mannschaft.app.event.event.EventStatusChangedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * イベント専用チャットチャンネルを自動管理するリスナー。
 *
 * <p>イベント作成時にチャットチャンネルを自動生成し、
 * イベント完了・キャンセル時にチャンネルをアーカイブする。</p>
 *
 * <h3>クロスドメイン境界</h3>
 * <p>本リスナーは {@code event} ドメインのイベントを受けて {@code chat} ドメインのサービスを呼び出す。
 * イベント駆動による疎結合のため CLAUDE.md 原則5（@Transactional はドメイン内に閉じる）には抵触しない。</p>
 *
 * <h3>非同期処理</h3>
 * <p>{@link Async}("event-pool") で非同期実行し、メインのトランザクションをブロックしない。
 * 失敗しても WARN ログに留め、サービス全体への影響を防ぐ（ベストエフォート）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EventChatChannelListener {

    private final EventChatChannelService eventChatChannelService;

    /**
     * イベント作成イベントを受信し、専用チャットチャンネルを自動生成する。
     *
     * @param event イベント作成ドメインイベント
     */
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "対応する gate_key が無く停止条件を宣言できないため常時実行する。イベントに紐づくチャットチャネルの生成・状態同期。機能単位の閉栓が要るようになった時点で gate_key の発行から検討すること")
    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onEventCreated(EventCreatedEvent event) {
        try {
            eventChatChannelService.createForEvent(
                    event.getEventId(), event.getScopeType(), event.getScopeId(), event.getTitle()
            );
        } catch (Exception e) {
            log.warn("イベントチャンネル自動作成に失敗: eventId={}", event.getEventId(), e);
        }
    }

    /**
     * イベントステータス変更イベントを受信し、完了またはキャンセル時にチャンネルをアーカイブする。
     *
     * @param event イベントステータス変更ドメインイベント
     */
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "対応する gate_key が無く停止条件を宣言できないため常時実行する。イベントに紐づくチャットチャネルの生成・状態同期。機能単位の閉栓が要るようになった時点で gate_key の発行から検討すること")
    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onEventStatusChanged(EventStatusChangedEvent event) {
        if (event.getNewStatus() == EventStatus.COMPLETED || event.getNewStatus() == EventStatus.CANCELLED) {
            try {
                eventChatChannelService.archiveForEvent(event.getEventId());
            } catch (Exception e) {
                log.warn("イベントチャンネルアーカイブに失敗: eventId={}", event.getEventId(), e);
            }
        }
    }
}
