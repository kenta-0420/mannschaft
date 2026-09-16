package com.mannschaft.app.pointcard.listener;

import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.organization.event.OrganizationDeletedEvent;
import com.mannschaft.app.pointcard.entity.PointCardProviderEntity;
import com.mannschaft.app.pointcard.event.ProviderCacheRefreshEvent;
import com.mannschaft.app.pointcard.repository.PointCardProviderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;

/**
 * 組織が論理削除された際、その組織が発行した自店プロバイダーを {@code is_active=false} に強制更新する。
 *
 * <p>設計書: {@code docs/features/F18_point_card_wallet.md} §12.3 / §15 Phase 2 拡張ポイント
 *
 * <p>カード本体（{@code user_point_cards}）は削除しない。顧客側に履歴・残スタンプを残し、
 * バッジを通じて「発行元組織は閉鎖された」旨のみ表示する運用とする（個人資産保護）。</p>
 *
 * <p>プロバイダー無効化後に {@link ProviderCacheRefreshEvent} を発火し、
 * {@code ProviderMatchService}（第二陣 2B）の fuzzy match キャッシュを再構築する。</p>
 *
 * <p>呼び出しタイミングは {@code AFTER_COMMIT} とし、組織削除トランザクション完了後に
 * 非同期で実行する。失敗してもコア機能（組織削除そのもの）はロールバックしない。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PointCardOrganizationDeletedListener {

    private final PointCardProviderRepository providerRepository;
    private final ApplicationEventPublisher eventPublisher;

    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "止めると削除済み組織を指すポイントカード提携プロバイダ行が孤児として残り、組織削除の整合性が壊れる。イベントは再生されないため取りこぼすと恒久的に残留する")
    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onOrganizationDeleted(OrganizationDeletedEvent event) {
        Long orgId = event.getOrganizationId();
        List<PointCardProviderEntity> providers =
                providerRepository.findAllByOrganizationIdAndActiveTrue(orgId);
        if (providers.isEmpty()) {
            return;
        }
        providers.forEach(p -> p.setActive(Boolean.FALSE));
        providerRepository.saveAll(providers);
        log.info("組織 {} の削除に伴い、自店ポイントカードプロバイダー {} 件を停止しました。",
                orgId, providers.size());
        // fuzzy match キャッシュ更新（自店プロバイダーが消えた状態に同期）
        eventPublisher.publishEvent(new ProviderCacheRefreshEvent());
    }
}
