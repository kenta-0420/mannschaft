package com.mannschaft.app.payment;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

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
     * イベントを冪等性テーブルへ登録し、ハンドラ実行可否を判定する。
     *
     * <p><b>冪等判定のセマンティクス（恒久 no-op の根治）:</b> スキップ（{@code false} 返却）と
     * するのは既存行が「確定済み」＝ {@code PROCESSED} / {@code IGNORED} の場合のみ。
     * {@code RECEIVED}（処理中にクラッシュして確定できなかった）や {@code FAILED}（dispatch 失敗）の
     * event_id は<b>再処理を許可</b>（{@code true} 返却）する。これにより一過性障害後の Stripe
     * 再送（at-least-once）でリカバリできる。失敗を握り潰して永久 no-op にしない。</p>
     *
     * <p>新規 event_id は {@code RECEIVED} を INSERT して {@code true} を返す。INSERT を冪等ゲートに
     * 使うことで、同一 event の並行受信は UNIQUE 制約で直列化される（TOCTOU を制約で守る）。
     * 並行受信で UNIQUE 競合した場合は、勝者がコミットした行の状態を読み直して再判定する
     * （確定済みなら {@code false}、未確定なら {@code true}＝再処理を許可）。</p>
     *
     * <p>{@code REQUIRES_NEW} で独立トランザクションにし、後続ハンドラがロールバックしても
     * 受信記録自体は確実に残す（at-least-once の再送に対し記録が消えない＝後続で FAILED 確定できる）。</p>
     *
     * @param eventId  Stripe イベント ID（{@code evt_xxx}）
     * @param type     イベント種別
     * @param livemode 本番/テスト区分
     * @return 処理（または再処理）すべきなら {@code true}、確定済み（真の重複）なら {@code false}
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean tryBegin(String eventId, String type, boolean livemode) {
        Optional<StripeWebhookEventEntity> existing = repository.findByEventId(eventId);
        if (existing.isPresent()) {
            return decideReprocess(eventId, existing.get().getProcessStatus());
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
            // 並行受信で UNIQUE 競合 → 勝者の確定状態を読み直して再判定（症状を隠さず情報ログに残す）。
            // 勝者が確定済みなら真の重複としてスキップ、未確定なら再処理を許可する。
            log.info("Webhook event_id UNIQUE 競合（並行受信）。状態を読み直して再判定します: eventId={}", eventId);
            WebhookProcessStatus current = repository.findByEventId(eventId)
                    .map(StripeWebhookEventEntity::getProcessStatus)
                    .orElse(WebhookProcessStatus.RECEIVED);
            return decideReprocess(eventId, current);
        }
    }

    /**
     * 既存行の状態から「再処理すべきか」を判定する。
     *
     * <p>{@code PROCESSED}/{@code IGNORED}（確定済み）はスキップ（{@code false}）。
     * {@code RECEIVED}（処理中クラッシュ）/{@code FAILED}（dispatch 失敗）は再処理を許可（{@code true}）。</p>
     */
    private boolean decideReprocess(String eventId, WebhookProcessStatus status) {
        if (status == WebhookProcessStatus.PROCESSED || status == WebhookProcessStatus.IGNORED) {
            log.info("Webhook 確定済み（真の重複受信）。スキップします: eventId={}, status={}", eventId, status);
            return false;
        }
        log.info("Webhook 未確定（過去に中断/失敗）。再処理を許可します: eventId={}, status={}", eventId, status);
        return true;
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

    /**
     * ハンドラ失敗時に {@code FAILED} を記録する（恒久 no-op の根治）。
     *
     * <p>本処理トランザクションがロールバックしても受信記録を {@code FAILED} で残すため
     * {@code REQUIRES_NEW} で独立コミットする。{@code FAILED} 行は次回 {@link #tryBegin} で
     * 再処理が許可されるため、Stripe 再送でリカバリできる。失敗を握り潰さない。</p>
     *
     * @param eventId Stripe イベント ID
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(String eventId) {
        repository.findByEventId(eventId).ifPresent(e -> {
            e.setProcessStatus(WebhookProcessStatus.FAILED);
            e.setProcessedAt(LocalDateTime.now());
            repository.save(e);
        });
    }
}
