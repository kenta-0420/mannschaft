package com.mannschaft.app.payment;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * F22.1 謝礼決済: Webhook 冪等性ゲート（Connect/Platform 共通）。
 *
 * <p>受信した {@code event_id} を {@code stripe_webhook_events} へ記録し、UNIQUE 制約で
 * 二重処理を物理拒否する。{@code event_id} が既存なら「既処理」として処理をスキップさせる
 * （設計書 01 §3.5 / 02 §4.1）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WebhookIdempotencyService {

    private final StripeWebhookEventRepository repository;

    /**
     * イベントを冪等性テーブルへ登録する。
     *
     * <p>新規登録に成功した場合のみ {@code true}（＝ハンドラ実行へ進む）を返す。
     * 既に同一 {@code event_id} が存在する／競合で UNIQUE 違反した場合は {@code false}
     * （＝既処理として no-op）を返す。INSERT を冪等ゲートに使うことで、同一 event の
     * 並行受信も一意制約で直列化される。</p>
     *
     * <p>{@code REQUIRES_NEW} で独立トランザクションにし、後続ハンドラがロールバックしても
     * 受信記録自体は確実に残す（at-least-once の再送に対し記録が消えない）。</p>
     *
     * @param eventId  Stripe イベント ID（{@code evt_xxx}）
     * @param type     イベント種別
     * @param livemode 本番/テスト区分
     * @return 新規受信なら {@code true}、既処理（重複）なら {@code false}
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean tryBegin(String eventId, String type, boolean livemode) {
        if (repository.existsByEventId(eventId)) {
            log.info("Webhook 既処理（重複受信）。スキップします: eventId={}", eventId);
            return false;
        }
        try {
            StripeWebhookEventEntity entity = StripeWebhookEventEntity.builder()
                    .eventId(eventId)
                    .type(type)
                    .livemode(livemode)
                    .processStatus(WebhookProcessStatus.RECEIVED)
                    .build();
            repository.saveAndFlush(entity);
            return true;
        } catch (DataIntegrityViolationException e) {
            // 並行受信で UNIQUE 競合 → 既処理として安全にスキップ（症状を隠さず情報ログに残す）
            log.info("Webhook event_id UNIQUE 競合（並行受信）。スキップします: eventId={}", eventId);
            return false;
        }
    }

    /**
     * ハンドラ完了後に処理状態を確定する。
     *
     * @param eventId Stripe イベント ID
     * @param status  確定状態（{@code PROCESSED}/{@code IGNORED}/{@code FAILED}）
     */
    @Transactional
    public void markProcessed(String eventId, WebhookProcessStatus status) {
        repository.findByEventId(eventId).ifPresent(e -> {
            e.setProcessStatus(status);
            e.setProcessedAt(LocalDateTime.now());
            repository.save(e);
        });
    }
}
