package com.mannschaft.app.proxy.batch;

import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.auth.entity.UserEntity.UserStatus;
import com.mannschaft.app.auth.event.UserStatusChangedEvent;
import com.mannschaft.app.proxy.service.ProxyConsentLifecycleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * ユーザーのライフイベントを受信して代理入力同意書を自動失効させるジョブ（F14.1 Phase 13-β）。
 * UserStatusChangedEvent を受信し、DECEASED または RELOCATED への変更時に全同意書を失効させる。
 * {@code @Async} で非同期実行し、元のトランザクションをブロックしない。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ProxyConsentLifeEventJob {

    /** 同意書を自動失効させるステータス（FROZEN/ARCHIVED は復帰可能なため対象外）。 */
    private static final Set<UserStatus> LIFE_EVENT_STATUSES =
            Set.of(UserStatus.DECEASED, UserStatus.RELOCATED);

    private final ProxyConsentLifecycleService lifecycleService;

    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "死亡・転居というライフイベントに応じた代理入力同意の失効処理であり、止めると本人が既に存在しないのに同意書が有効なまま残る。イベントは再生されないため取りこぼすと恒久的に是正されない")
    @EventListener
    @Async("event-pool")
    public void onUserStatusChanged(UserStatusChangedEvent event) {
        if (!LIFE_EVENT_STATUSES.contains(event.getNewStatus())) {
            return;
        }
        log.info("ライフイベント検知: userId={}, newStatus={}", event.getUserId(), event.getNewStatus());
        String reason = "ユーザーステータス変更: " + event.getNewStatus().name();
        lifecycleService.revokeAllForUser(event.getUserId(), reason);
    }
}
