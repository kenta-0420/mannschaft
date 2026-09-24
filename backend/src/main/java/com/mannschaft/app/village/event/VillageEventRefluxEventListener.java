package com.mannschaft.app.village.event;

import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.village.service.VillageEventFeedRefluxService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * F17.2 Wave2 ① 村行事→村フィード自動還流の EventListener（設計書 §3.3.1）。
 *
 * <p>{@link VillageEventOccurredEvent} を <b>本体トランザクションのコミット後</b>
 * （{@link TransactionPhase#AFTER_COMMIT}）に購読し、村フィードへの自動投稿・通知を発火する。</p>
 *
 * <h2>なぜ {@code REQUIRES_NEW} か</h2>
 * <p>社内前例 {@code VillageUserCleanerEventListener} と同じ {@code AFTER_COMMIT} + {@code REQUIRES_NEW}。
 * {@code afterCommit} 実行中は元トランザクションの synchronization がまだアクティブなため、
 * REQUIRED で新規トランザクションを開こうとすると「synchronization already active」で失敗する。
 * {@code REQUIRES_NEW} は現在の synchronization を suspend してから新規トランザクションを張るため
 * これを回避でき、副作用（自動投稿・通知）を本体と独立したトランザクションで確定できる
 * （本体は既にコミット済みなので巻き戻らない）。</p>
 *
 * <h2>非同期化（fan-out 抜本改修 P1・AC-7）</h2>
 * <p>本リスナーは {@code @Async} で<b>リクエストスレッド外</b>で実行する。村行事作成 API の応答を
 * 受信者数（最大 50 万人）ぶんの通知 fan-out から切り離し、API は還流の完了を待たずに即座に返す。
 * 還流自体は {@code AFTER_COMMIT} で本体コミット後に走り、{@code REQUIRES_NEW} で独立トランザクションを
 * 張る（本体は既にコミット済みなので巻き戻らない）。fan-out の書き込みはさらにチャンク単位で
 * 独立コミットされる（{@code NotificationBulkFanoutService}）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VillageEventRefluxEventListener {

    private final VillageEventFeedRefluxService refluxService;

    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "対応する gate_key が無く停止条件を宣言できないため常時実行する。村の出来事の還流処理。機能単位の閉栓が要るようになった時点で gate_key の発行から検討すること")
    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onVillageEventOccurred(VillageEventOccurredEvent event) {
        refluxService.publish(event.villageId(), event.type(), event.sourceEventUuid(),
                event.eventTitle(), event.actionUrl());
    }
}
