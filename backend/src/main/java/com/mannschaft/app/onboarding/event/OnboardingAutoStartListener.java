package com.mannschaft.app.onboarding.event;

import com.mannschaft.app.onboarding.service.OnboardingProgressService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * メンバー参加時のオンボーディング自動開始リスナー。
 * MemberJoinedEvent を購読し、ACTIVEテンプレートが存在する場合にオンボーディングを開始する。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OnboardingAutoStartListener {

    private final OnboardingProgressService progressService;

    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void handleMemberJoined(MemberJoinedEvent event) {
        log.info("メンバー参加イベント受信: userId={}, scope={}/{}",
                event.getUserId(), event.getScopeType(), event.getScopeId());
        try {
            progressService.startOnboarding(event.getUserId(), event.getScopeType(), event.getScopeId());
            log.info("オンボーディング自動開始成功: userId={}", event.getUserId());
        } catch (Exception e) {
            log.warn("オンボーディング自動開始スキップ: userId={}, reason={}",
                    event.getUserId(), e.getMessage());
        }
    }
}
