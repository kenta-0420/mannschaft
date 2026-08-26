package com.mannschaft.app.village.event;

import com.mannschaft.app.village.entity.enums.VillageEventNotificationType;

import java.util.UUID;

/**
 * F17.2 Wave2 ① 村行事の発生イベント（設計書 §3.3.1）。
 *
 * <p>行事の作成・確定など {@code @Transactional} メソッド内から発行し、
 * {@link VillageEventRefluxEventListener} が {@code AFTER_COMMIT} + {@code REQUIRES_NEW} で購読して
 * 村フィードへの自動投稿・通知を発火する。本体トランザクションのコミット後に副作用が走るため、
 * 副作用の失敗が本体を巻き戻さない（{@code TransactionSynchronization#afterCommit} 内から
 * REQUIRED で新規トランザクションを開く「synchronization already active」問題も回避する）。</p>
 *
 * @param villageId       対象村 UUID
 * @param type            通知種別（{@code system_post_type} にも {@code .name()} で格納）
 * @param sourceEventUuid 対象行事 UUID（歳時記/祭/寄合の id）
 * @param eventTitle      行事の表題（本文組み立て用）
 * @param actionUrl       通知タップ先（村・行事 UUID を含む相対 URL）
 */
public record VillageEventOccurredEvent(
        UUID villageId,
        VillageEventNotificationType type,
        UUID sourceEventUuid,
        String eventTitle,
        String actionUrl) {
}
