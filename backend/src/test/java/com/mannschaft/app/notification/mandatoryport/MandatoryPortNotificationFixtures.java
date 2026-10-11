package com.mannschaft.app.notification.mandatoryport;

import com.mannschaft.app.notification.fanout.FanoutEnqueueCommand;
import com.mannschaft.app.notification.fanout.NotificationFanoutJobService;
import com.mannschaft.app.notification.outbox.NotificationOutboxIngestService;

/**
 * D-3P-3（{@code CrossDomainMandatoryPropagationArchTest}）の検体。通知ドメインの内側（陰性）。
 * 実行はしない（ArchUnit が読むだけ）。
 */
public final class MandatoryPortNotificationFixtures {

    private MandatoryPortNotificationFixtures() {
    }

    /** 通知ドメイン内からの登録・取り込みサービスへの依存は対象外。 */
    public static class InternalEnqueuer {
        NotificationFanoutJobService fanoutJobService;
        NotificationOutboxIngestService ingestService;

        public void enqueue(FanoutEnqueueCommand command) {
            fanoutJobService.enqueueInCurrentTransaction(command);
        }
    }
}
