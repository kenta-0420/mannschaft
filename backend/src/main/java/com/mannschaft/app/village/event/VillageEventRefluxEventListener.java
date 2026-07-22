package com.mannschaft.app.village.event;

import com.mannschaft.app.village.service.VillageEventFeedRefluxService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
 * <p>本リスナーは同期実行（{@code @Async} を付けない）。還流の結果は行事作成 API の応答が返る前に
 * 確定するため、結合テストで決定論的に検証できる。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VillageEventRefluxEventListener {

    private final VillageEventFeedRefluxService refluxService;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onVillageEventOccurred(VillageEventOccurredEvent event) {
        refluxService.publish(event.villageId(), event.type(), event.sourceEventUuid(),
                event.eventTitle(), event.actionUrl());
    }
}
