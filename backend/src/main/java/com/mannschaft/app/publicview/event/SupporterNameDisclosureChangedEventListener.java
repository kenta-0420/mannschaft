package com.mannschaft.app.publicview.event;

import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.publicview.metrics.PublicViewMetricsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * F19.1 Phase 5: {@link SupporterNameDisclosureChangedEvent} リスナー。
 *
 * <p>サポーター氏名表示モードの変更を監視し、以下を実行する:</p>
 * <ol>
 *   <li>Micrometer の Counter にモード変更を記録する（{@link PublicViewMetricsService#recordModeChange}）</li>
 *   <li>INFO ログで変更内容を出力する（監査証跡）</li>
 * </ol>
 *
 * <p><strong>注意: スナップショットの遡及生成は一切行わない。</strong><br>
 * 非対称切替ルール（設計書 §7.7 厳命）により、モード変更は以降の新規投稿にのみ影響し、
 * 既存スナップショットを書き換えることは禁止されている。</p>
 *
 * <p>設計書: docs/features/F19.1_public_pages_identity_disclosure.md §6 / §7.7</p>
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class SupporterNameDisclosureChangedEventListener {

    private final PublicViewMetricsService metricsService;

    /**
     * サポーター氏名表示モード変更イベントを処理する。
     *
     * <p>{@code @Async("event-pool")} により非同期（event-pool スレッド）で実行される。
     * {@code AFTER_COMMIT} フェーズで動作するため、DB の変更がコミット済みであることが保証される。</p>
     *
     * @param event モード変更イベント
     */
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "対応する gate_key が無く停止条件を宣言できないため常時実行する。サポーター名の公開設定変更の反映。機能単位の閉栓が要るようになった時点で gate_key の発行から検討すること")
    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleSupporterNameDisclosureChanged(
            SupporterNameDisclosureChangedEvent event) {

        // 1. スコープ種別判定（チームまたは組織）
        String scopeType = event.getTeamId() != null ? "TEAM" : "ORGANIZATION";
        Long scopeId = event.getTeamId() != null
                ? event.getTeamId() : event.getOrganizationId();

        // 2. メトリクス記録
        metricsService.recordModeChange(
                event.getOldMode(), event.getNewMode(), scopeType);

        // 3. 監査ログ（INFO レベル）
        log.info("サポーター氏名表示モードが変更されました: " +
                "scope_type={}, scope_id={}, {} -> {}, operator_id={}",
                scopeType, scopeId,
                event.getOldMode(), event.getNewMode(),
                event.getOperatorUserId());

        // NOTE: スナップショットの遡及生成は行わない（設計書 §7.7 厳命）
        // 非対称切替ルールにより、モード変更は以降の新規投稿にのみ影響する。
    }
}
