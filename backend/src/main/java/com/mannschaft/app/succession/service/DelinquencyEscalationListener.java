package com.mannschaft.app.succession.service;

import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.payment.event.PaymentStatusChangedEvent;
import com.mannschaft.app.resident.repository.ResidentRegistryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * 支払いステータス変更イベントのリスナー（F09.15 S5-A）。
 *
 * <p>設計書: {@code docs/features/F09.15_resident_succession_support.md} §7.4
 *
 * <p>F08.2 が発行する {@link PaymentStatusChangedEvent} を購読し、
 * 滞納判定（{@code delinquent = true}）の場合に
 * {@link DelinquencyEscalationService#createEscalation} を呼んでエスカレーションを生成する。
 *
 * <p>TODO: paymentドメインとsuccessドメインをまたぐ @EventListener 連携。
 * 将来はメッセージキュー（RabbitMQ / Kafka）で分離予定。
 *
 * <p>設計上の注意: {@code userId} → {@code residentRegistryId} / {@code dwellingUnitId} の変換は
 * residentドメインの {@link ResidentRegistryRepository} を経由する。
 * TODO: residentドメインとsuccessドメインをまたぐ依存。将来はイベント駆動で分離予定。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DelinquencyEscalationListener {

    private final DelinquencyEscalationService escalationService;
    private final ResidentRegistryRepository residentRegistryRepository;

    /**
     * 支払いステータス変更イベントを処理する。
     *
     * <p>非同期実行（{@code @Async}）により、paymentドメインのトランザクションと分離する。
     *
     * <p>処理フロー:
     * <ol>
     *   <li>滞納フラグ確認: {@code event.isDelinquent()} が false の場合はスキップ</li>
     *   <li>{@code userId} から有効な居住者台帳エントリを取得</li>
     *   <li>居住者が見つからない場合は警告ログを出力してスキップ</li>
     *   <li>{@link DelinquencyEscalationService#createEscalation} を呼び出し（冪等）</li>
     * </ol>
     *
     * @param event 支払いステータス変更イベント
     */
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "殿の裁定: 上流の payment は別キーのため閉栓中も滞納が確定しうる。日次バッチは既存行の昇格しか行わずエスカレーションの新規生成は本リスナーが唯一の経路であり、落とすと取り返せない")
    @EventListener
    @Async
    public void onPaymentStatusChanged(PaymentStatusChangedEvent event) {
        if (!event.isDelinquent()) {
            return;
        }

        log.info("滞納イベント受信: userId={}, organizationId={}, validUntil={}",
                event.getUserId(), event.getOrganizationId(), event.getValidUntil());

        // userId → residentRegistryId / dwellingUnitId の変換
        // TODO: residentドメインとsuccessドメインをまたぐ依存。将来はResidentDelinquentEventで分離予定
        residentRegistryRepository
                .findActiveByUserIdAndOrganizationId(event.getUserId(), event.getOrganizationId())
                .ifPresentOrElse(
                        registry -> {
                            escalationService.createEscalation(
                                    event.getOrganizationId(),
                                    registry.getId(),
                                    registry.getDwellingUnitId(),
                                    event.getValidUntil());
                            log.info("エスカレーション生成完了: residentRegistryId={}", registry.getId());
                        },
                        () -> log.warn(
                                "滞納イベント受信したが居住者台帳が見つかりません。スキップします: userId={}, organizationId={}",
                                event.getUserId(), event.getOrganizationId()));
    }
}
