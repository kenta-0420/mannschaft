package com.mannschaft.app.moduleevent;

import java.time.Clock;
import org.springframework.modulith.events.core.DefaultEventPublicationRegistry;
import org.springframework.modulith.events.core.EventPublicationRepository;

/** 保存対象の制約と試行台帳を接続する Registry。 */
public class RestrictedEventPublicationRegistry extends DefaultEventPublicationRegistry {
    public RestrictedEventPublicationRegistry(EventPublicationRepository repository, Clock clock) {
        super(repository, clock);
    }

    /** 終了時に未完了 payload を全件取り込まない。観測は集計クエリが担う。 */
    @Override
    public void destroy() {
    }
}
