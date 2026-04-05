package com.mannschaft.app.config;

import com.mannschaft.app.common.DomainEventPublisher;
import com.mannschaft.app.common.event.DomainEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * Spring ApplicationEvent を利用したドメインイベント発行実装。
 *
 * <p>異なる機能間の非同期連携に使用する。
 * {@link DomainEventPublisher} インターフェースの唯一の実装。</p>
 */
@Component
@RequiredArgsConstructor
public class SpringEventPublisher implements DomainEventPublisher {

    private final ApplicationEventPublisher applicationEventPublisher;

    @Override
    public void publish(DomainEvent event) {
        applicationEventPublisher.publishEvent(event);
    }
}
