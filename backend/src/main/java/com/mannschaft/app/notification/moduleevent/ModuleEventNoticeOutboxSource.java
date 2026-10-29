package com.mannschaft.app.notification.moduleevent;

import com.mannschaft.app.moduleevent.api.ModuleEventNoticeOutboxApi;
import com.mannschaft.app.notification.outbox.NotificationOutboxMessage;
import com.mannschaft.app.notification.outbox.NotificationOutboxPayload;
import com.mannschaft.app.notification.fanout.FanoutEnqueueCommand;
import com.mannschaft.app.notification.fanout.FanoutMessageKind;
import com.mannschaft.app.notification.NotificationPriority;
import com.mannschaft.app.moduleevent.api.dto.ModuleEventNoticeClaim;
import com.mannschaft.app.moduleevent.api.dto.ModuleEventNoticeDescriptor;
import com.mannschaft.app.notification.outbox.NotificationOutboxPayloadCodec;
import com.mannschaft.app.notification.outbox.NotificationOutboxSource;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** L1-M の公開窓口を既存通知 relay に接続する。 */
@Component
@RequiredArgsConstructor
public class ModuleEventNoticeOutboxSource implements NotificationOutboxSource {
    public static final String SOURCE_NAME = "moduleevent";
    private final ModuleEventNoticeOutboxApi api;
    private final NotificationOutboxPayloadCodec codec;

    @Override public String name() { return SOURCE_NAME; }
    @Override public List<NotificationOutboxMessage> claim(int limit, Instant now) {
        return api.claim(limit, now).stream().map(this::message).toList();
    }

    private NotificationOutboxMessage message(ModuleEventNoticeClaim claim) {
        // 未知版はdescriptorに触らず元の版のままrelayへ返す。既知破損も他行のclaimを妨げない。
        String payload = "{}";
        if (claim.getDescriptorVersion() == NotificationOutboxPayloadCodec.CURRENT_VERSION
                && claim.getDescriptor().isPresent()) {
            ModuleEventNoticeDescriptor descriptor = claim.getDescriptor().get();
            if (descriptor.getNoticeRef().equals(claim.getNoticeRef()) && descriptor.getNoticeRef() > 0) {
                var command = new FanoutEnqueueCommand(ModuleEventNoticeRecipientSource.SCOPE_TYPE,
                        descriptor.getNoticeRef().toString(), "MODULE_EVENT_NOTICE", descriptor.getNoticeKey(),
                        descriptor.getImpact().getOrganizationId(), NotificationPriority.NORMAL, null,
                        "MODULE_EVENT_NOTICE", descriptor.getNoticeRef(), null, false,
                        FanoutMessageKind.valueOf("MODULE_EVENT_" + descriptor.getPhase().name()), List.of(),
                        FanoutEnqueueCommand.ShardMode.FIXED_SINGLE);
                payload = codec.encode(NotificationOutboxPayload.fanout(command));
            }
        }
        return new NotificationOutboxMessage(claim.getId(), claim.getClaimToken(), "FANOUT",
                claim.getDescriptorVersion(), payload, claim.getAttemptCount());
    }
    @Override public boolean markRelayed(UUID id, UUID token, Instant now) { return api.markRelayed(id, token, now); }
    @Override public boolean markFailed(UUID id, UUID token, String error, Instant next, boolean dead, Instant now) {
        return api.markFailed(id, token, next, dead, now);
    }
    @Override public boolean deferUnsupportedVersion(UUID id, UUID token, Instant next, Instant now) {
        return api.deferUnsupportedVersion(id, token, next, now);
    }
    @Override public int recoverStuck(Instant before, Instant now) { return api.recoverStuck(before, now); }
    /** 強い保持 gate は未提供。時刻だけによる削除をしない。 */
    @Override public int sweep(Instant relayedBefore, Instant deadBefore) {
        throw new UnsupportedOperationException("L1-M retention gate は未提供。通知予約を保持する");
    }
    @Override public Optional<Instant> oldestPendingCreatedAt() { return api.oldestPendingCreatedAt(); }
}
